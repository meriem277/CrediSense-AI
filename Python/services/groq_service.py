# services/groq_service.py
"""
Service d'extraction CrediSense
- Appels LLM via le routeur multi-fournisseurs (services/llm_client.py)
  → Ollama local / Mistral / Groq avec bascule automatique sur 429
- Cache par hash MD5 du texte
- Validation Pydantic du JSON extrait
- Découpage intelligent du texte (par paragraphes)
- Score de confiance CONTEXTUEL par type de document
- Une extraction vide (ou tout à null) n'est JAMAIS renvoyée comme SUCCESS :
  si Ollama renvoie un JSON vide, on retente automatiquement avec Mistral
"""

import json
import logging
import time
import hashlib
from typing import Optional

import httpx
from pydantic import BaseModel

from config import MISTRAL_API_KEY
from services.llm_client import chat_completion, LLMUnavailableError, etat as llm_etat

logger = logging.getLogger(__name__)

# ── Limites ───────────────────────────────────────────────────────────────────
MAX_TEXTE_CHARS = 4000   # limite caractères envoyés au LLM
CACHE_MAX_SIZE  = 256    # entrées en cache

# ── Schema Pydantic — validation stricte ──────────────────────────────────────
class ExtractionFinanciere(BaseModel):
    nomClient:          Optional[str]   = None
    prenomClient:       Optional[str]   = None
    cin:                Optional[str]   = None
    revenuMensuelNet:   Optional[float] = None
    typeContrat:        Optional[str]   = None
    employeur:          Optional[str]   = None
    anciennete:         Optional[str]   = None
    chargesMensuelles:  Optional[float] = None
    tauxEndettement:    Optional[float] = None
    montantCredit:      Optional[float] = None
    dureeCredit:        Optional[int]   = None
    typeCredit:         Optional[str]   = None
    soldeMoyenCompte:   Optional[float] = None
    historiqueCredit:   Optional[str]   = None
    incidentsPayment:   Optional[int]   = None

    class Config:
        extra = "ignore"  # ignorer les champs inconnus retournés par le LLM

# ── Prompts ───────────────────────────────────────────────────────────────────
SYSTEM_PROMPT = """Tu es un expert en analyse de dossiers de crédit bancaire pour Attijari Bank Tunisie.
Ton rôle est d'extraire les informations financières clés du texte fourni.
Réponds UNIQUEMENT avec un objet JSON valide, sans texte avant ou après, sans balises markdown.
Si une information est absente, utilise null pour ce champ.
Pour typeContrat utilise : CDI, CDD, FONCTIONNAIRE, INDEPENDANT, RETRAITE.
Pour typeCredit utilise : IMMOBILIER, CONSOMMATION.
Pour historiqueCredit utilise : BON, MOYEN, MAUVAIS."""

JSON_SCHEMA = """{
  "nomClient": "string | null",
  "prenomClient": "string | null",
  "cin": "string | null",
  "revenuMensuelNet": "number | null",
  "typeContrat": "CDI|CDD|FONCTIONNAIRE|INDEPENDANT|RETRAITE | null",
  "employeur": "string | null",
  "anciennete": "string | null",
  "chargesMensuelles": "number | null",
  "tauxEndettement": "number | null",
  "montantCredit": "number | null",
  "dureeCredit": "number | null",
  "typeCredit": "IMMOBILIER|CONSOMMATION | null",
  "soldeMoyenCompte": "number | null",
  "historiqueCredit": "BON|MOYEN|MAUVAIS | null",
  "incidentsPayment": "number | null"
}"""

# ── Champs pertinents par type de document ────────────────────────────────────
# Utilisé pour ne pas pénaliser un document dont certains champs
# financiers sont structurellement absents (ex : un CIN n'aura jamais
# de revenuMensuelNet).
CHAMPS_ATTENDUS_PAR_TYPE = {
    "CIN": {
        "nomClient", "prenomClient", "cin"
    },
    "FICHE_PAIE": {
        "nomClient", "prenomClient", "revenuMensuelNet", "typeContrat",
        "employeur", "anciennete"
    },
    "RELEVE_BANCAIRE": {
        "nomClient", "soldeMoyenCompte", "historiqueCredit", "incidentsPayment"
    },
    "ATTESTATION_EMPLOI": {
        "nomClient", "prenomClient", "employeur", "typeContrat", "anciennete"
    },
    "JUSTIFICATIF_DOMICILE": {
        "nomClient", "prenomClient"
    },
    # Fallback si le type n'est pas reconnu / pas encore mappé :
    # on retombe sur tous les champs du schéma (comportement d'origine).
    "_DEFAULT": set(ExtractionFinanciere.model_fields.keys())
}

# Champs financiers "critiques" — comptent double dans le score,
# uniquement s'ils font partie des champs attendus pour ce type de document.
CHAMPS_CRITIQUES = {
    "revenuMensuelNet", "typeContrat", "tauxEndettement",
    "montantCredit", "historiqueCredit"
}

# ── Extraction adaptée au type de document ───────────────────────────────────
# Chaque type de document ne contient qu'une partie des champs : demander les 15 champs
# sur une CIN pousse le modèle à en inventer. Le type vient de la classification (ou, à
# défaut, de l'emplacement choisi par le client) ; sans type connu on garde le schéma complet.
CHAMPS_SCHEMA = {
    "nomClient":         '"string | null"',
    "prenomClient":      '"string | null"',
    "cin":               '"string | null"',
    "revenuMensuelNet":  '"number | null"',
    "typeContrat":       '"CDI|CDD|FONCTIONNAIRE|INDEPENDANT|RETRAITE | null"',
    "employeur":         '"string | null"',
    "anciennete":        '"string | null"',
    "chargesMensuelles": '"number | null"',
    "tauxEndettement":   '"number | null"',
    "montantCredit":     '"number | null"',
    "dureeCredit":       '"number | null"',
    "typeCredit":        '"IMMOBILIER|CONSOMMATION | null"',
    "soldeMoyenCompte":  '"number | null"',
    "historiqueCredit":  '"BON|MOYEN|MAUVAIS | null"',
    "incidentsPayment":  '"number | null"',
}

# Toujours demandés : servent à la cohérence du CIN et à la vérification d'identité
CHAMPS_TOUJOURS = {"nomClient", "prenomClient", "cin"}

# Champs ajoutés au-delà de CHAMPS_ATTENDUS_PAR_TYPE (utiles sans entrer dans le score de confiance)
CHAMPS_EXTRA_PAR_TYPE = {
    "RELEVE_BANCAIRE": {"chargesMensuelles"},
}

CONSIGNES_PAR_TYPE = {
    "CIN": "Lis le numéro de la carte (8 chiffres), le nom et le prénom. "
           "Si le nom existe en arabe et en lettres latines, donne les lettres latines.",
    "FICHE_PAIE": "revenuMensuelNet = le « net à payer » du mois, jamais le salaire brut ni le net imposable. "
                  "anciennete = la date d'entrée ou l'ancienneté telle qu'écrite sur la fiche.",
    "RELEVE_BANCAIRE": "chargesMensuelles = total mensuel des échéances de crédit ou de prêt (pas le salaire, pas les "
                       "dépenses courantes). incidentsPayment = nombre de rejets, impayés ou découverts. "
                       "soldeMoyenCompte = la moyenne si elle est indiquée, sinon le dernier solde.",
    "ATTESTATION_EMPLOI": "anciennete = la date d'embauche ou la durée, telle qu'écrite dans l'attestation. "
                          "typeContrat uniquement si l'attestation le dit.",
    "JUSTIFICATIF_DOMICILE": "Seuls le nom et le prénom du titulaire nous intéressent.",
}


def schema_pour_type(type_document: Optional[str]) -> str:
    """Schéma JSON demandé au LLM : limité aux champs que ce type de document peut contenir."""
    type_document = (type_document or "").upper()
    attendus = CHAMPS_ATTENDUS_PAR_TYPE.get(type_document)
    if not attendus or type_document == "_DEFAULT":
        return JSON_SCHEMA
    retenus = attendus | CHAMPS_TOUJOURS | CHAMPS_EXTRA_PAR_TYPE.get(type_document, set())
    lignes = [f'  "{nom}": {desc}' for nom, desc in CHAMPS_SCHEMA.items() if nom in retenus]
    return "{\n" + ",\n".join(lignes) + "\n}"


def consigne_pour_type(type_document: Optional[str]) -> str:
    """Phrase(s) ajoutée(s) au prompt : de quel document il s'agit et comment lire ses champs."""
    type_document = (type_document or "").upper()
    consigne = CONSIGNES_PAR_TYPE.get(type_document)
    if not consigne:
        return ""
    return (f"Ce document a été identifié comme : {type_document}. {consigne} "
            f"Mets null pour tout champ que ce document ne contient pas : n'invente rien.\n\n")


# ── Compatibilité : client HTTP singleton ─────────────────────────────────────
# Conservé car d'autres fichiers l'importent encore
# (ex : services/llm_classifier_service.py).
_http_client: Optional[httpx.Client] = None

def get_http_client() -> httpx.Client:
    """Client HTTP singleton vers Mistral — réutilisé par les autres services."""
    global _http_client
    if _http_client is None or _http_client.is_closed:
        _http_client = httpx.Client(
            timeout=httpx.Timeout(30.0, connect=5.0),
            headers={
                "Authorization": f"Bearer {MISTRAL_API_KEY}",
                "Content-Type":  "application/json"
            }
        )
        logger.info("Client HTTP Mistral initialisé")
    return _http_client


class GroqService:

    # ── Cache en mémoire ──────────────────────────────────────────────────────
    _cache: dict = {}

    # ── Point d'entrée principal ──────────────────────────────────────────────

    def extraire_json(self, texte_nettoye: str, cin: str = "", type_document: Optional[str] = None) -> dict:
        """
        Extrait les données financières structurées depuis le texte OCR.

        Args:
            texte_nettoye: texte nettoyé après OCR
            cin:           CIN du client (pour le cache et la réponse)
            type_document: type du document (CIN, FICHE_PAIE, RELEVE_BANCAIRE,
                            ATTESTATION_EMPLOI, JUSTIFICATIF_DOMICILE...) issu de
                            la classification NLP — utilisé pour calculer un score
                            de confiance contextuel plutôt que global.

        Returns:
            {
              "json_data":        dict,
              "confidence_score": float,
              "cin":              str,
              "statut":           "SUCCESS" | "FAILURE",
              "provider":         str (mistral | groq | ollama),
              "duree_ms":         float,
              "depuis_cache":     bool
            }
        """
        t0 = time.time()

        if not texte_nettoye or len(texte_nettoye.strip()) < 10:
            return self._erreur("Texte OCR vide ou trop court", cin)

        # ── 1. Vérifier le cache ─────────────────────────────────────────────
        # Le hash inclut le type_document : un même texte classé différemment
        # (rare, mais possible) doit recalculer une confiance différente.
        cache_key = self._hash(texte_nettoye, type_document)
        if cache_key in self._cache:
            logger.info("Cache HIT extraction — %.0fms", (time.time()-t0)*1000)
            result                 = self._cache[cache_key].copy()
            result["depuis_cache"] = True
            result["duree_ms"]     = round((time.time()-t0)*1000, 1)
            return result

        # ── 2. Découper le texte intelligemment ─────────────────────────────
        texte_optimise = self._tronquer_intelligent(texte_nettoye)

        try:
            # ── 3. Appeler le LLM (bascule automatique entre fournisseurs) ───
            reponse = self._appeler_llm(texte_optimise, type_document=type_document)

            # ── 4. Nettoyer et parser ────────────────────────────────────────
            json_dict = self._nettoyer_json(reponse.content)

            # ── 4b. Réponse vide ou « tout à null » → on retente avec Mistral ─
            # Un petit modèle local renvoie parfois un JSON valide mais vide.
            if self._nb_champs_remplis(json_dict) == 0 and reponse.provider != "mistral":
                logger.warning(
                    "Extraction %s vide (tous les champs à null) — nouvel essai avec Mistral",
                    reponse.provider
                )
                try:
                    reponse   = self._appeler_llm(texte_optimise, route=["mistral"], type_document=type_document)
                    json_dict = self._nettoyer_json(reponse.content)
                except LLMUnavailableError as e:
                    logger.warning("Nouvel essai Mistral impossible : %s", str(e))

            if self._nb_champs_remplis(json_dict) == 0:
                return self._erreur(
                    f"Aucune information extraite du document (réponse {reponse.provider} vide)", cin
                )

            # ── 5. Valider avec Pydantic ─────────────────────────────────────
            try:
                validated = ExtractionFinanciere(**json_dict)
                json_dict = validated.model_dump(exclude_none=False)
            except Exception as e:
                logger.warning("Validation Pydantic partielle : %s", str(e))

            # ── 6. Score de confiance CONTEXTUEL ─────────────────────────────
            confidence = self._calculer_confidence(json_dict, type_document)

            duree_ms = round((time.time() - t0) * 1000, 1)
            logger.info(
                "Extraction OK — provider=%s, type=%s, confidence=%.2f, %d champs remplis, %.0fms",
                reponse.provider,
                type_document or "INCONNU",
                confidence,
                sum(1 for v in json_dict.values() if v is not None),
                duree_ms
            )

            result = {
                "json_data":        json_dict,
                "confidence_score": confidence,
                "cin":              cin,
                "statut":           "SUCCESS",
                "provider":         reponse.provider,
                "duree_ms":         duree_ms,
                "depuis_cache":     False
            }

            # ── 7. Mettre en cache (uniquement les succès) ───────────────────
            self._cache_set(cache_key, result)
            return result

        except LLMUnavailableError as e:
            logger.error("Extraction impossible : %s", str(e))
            return self._erreur(str(e), cin)

        except Exception as e:
            logger.error("Erreur extraction : %s", str(e), exc_info=True)
            return self._erreur(str(e), cin)

    # ── Appel LLM ─────────────────────────────────────────────────────────────

    def _appeler_llm(self, texte: str, route: Optional[list] = None, type_document: Optional[str] = None):
        """Appelle le routeur LLM (tâche 'extraction') en mode JSON."""
        user_prompt = (
            f"Extrais les informations financières du document bancaire "
            f"ci-dessous et retourne UNIQUEMENT un JSON selon ce schema :\n\n"
            f"{schema_pour_type(type_document)}\n\n"
            f"{consigne_pour_type(type_document)}"
            f"Document :\n{texte}"
        )

        return chat_completion(
            task="extraction",
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user",   "content": user_prompt}
            ],
            temperature=0.1,
            max_tokens=1200,
            json_mode=True,
            route=route
        )

    @staticmethod
    def _nb_champs_remplis(json_dict: dict) -> int:
        """Nombre de champs réellement remplis (ni null, ni chaîne vide)."""
        if not isinstance(json_dict, dict):
            return 0
        return sum(1 for v in json_dict.values() if v not in (None, "", [], {}))

    # ── Découpage intelligent du texte ────────────────────────────────────────

    def _tronquer_intelligent(self, texte: str) -> str:
        """
        Découpe le texte par paragraphes et garde les plus pertinents.
        Évite de couper au milieu d'une information importante.
        """
        if len(texte) <= MAX_TEXTE_CHARS:
            return texte

        # Mots-clés financiers importants
        mots_cles = [
            "salaire", "revenu", "net", "brut", "contrat", "emploi",
            "cin", "nom", "prénom", "charge", "endettement", "crédit",
            "mensuel", "bancaire", "solde", "incident", "historique",
            "راتب", "دخل", "عقد", "هوية"  # arabe
        ]

        paragraphes = [p.strip() for p in texte.split("\n") if p.strip()]
        scores      = []

        for para in paragraphes:
            score = sum(1 for mot in mots_cles if mot.lower() in para.lower())
            scores.append((score, para))

        # Trier par score décroissant, prendre les meilleurs
        scores.sort(key=lambda x: x[0], reverse=True)
        texte_optimise = "\n".join(p for _, p in scores)

        # Tronquer si toujours trop long
        if len(texte_optimise) > MAX_TEXTE_CHARS:
            texte_optimise = texte_optimise[:MAX_TEXTE_CHARS]

        logger.debug("Texte tronqué : %d → %d chars", len(texte), len(texte_optimise))
        return texte_optimise

    # ── Nettoyage JSON ────────────────────────────────────────────────────────

    def _nettoyer_json(self, texte: str) -> dict:
        """Nettoie la réponse du LLM et parse le JSON."""
        propre = (texte or "").strip()

        # Supprimer balises markdown
        for prefix in ("```json", "```"):
            if propre.startswith(prefix):
                propre = propre[len(prefix):]
                break
        if propre.endswith("```"):
            propre = propre[:-3]
        propre = propre.strip()

        # Extraire le premier objet JSON valide
        debut = propre.find("{")
        fin   = propre.rfind("}")
        if debut >= 0 and fin > debut:
            propre = propre[debut:fin+1]

        try:
            resultat = json.loads(propre)
            return resultat if isinstance(resultat, dict) else {}
        except json.JSONDecodeError:
            # Tentative de réparation JSON basique
            propre = propre.replace("'", '"').replace("None", "null")
            try:
                resultat = json.loads(propre)
                return resultat if isinstance(resultat, dict) else {}
            except Exception:
                logger.warning("JSON non parsable — retour dict vide")
                return {}

    # ── Score de confiance CONTEXTUEL ─────────────────────────────────────────

    def _calculer_confidence(self, json_data: dict, type_document: Optional[str] = None) -> float:
        """
        Score pondéré, calculé UNIQUEMENT sur les champs pertinents pour le
        type de document analysé.

        Un CIN n'est pas pénalisé pour ne pas contenir de revenuMensuelNet
        ou de montantCredit, puisque ces informations n'existent
        structurellement pas sur ce document.

        Args:
            json_data:     dict des champs extraits (peut contenir des None)
            type_document: type du document (CIN, FICHE_PAIE, ...). Si None
                           ou non reconnu, on retombe sur tous les champs du
                           schéma.
        """
        if not json_data:
            return 0.0

        champs_pertinents = CHAMPS_ATTENDUS_PAR_TYPE.get(
            type_document, CHAMPS_ATTENDUS_PAR_TYPE["_DEFAULT"]
        )

        total_poids   = 0
        poids_remplis = 0

        for champ, valeur in json_data.items():
            if champ not in champs_pertinents:
                continue  # champ hors périmètre de ce type de document — ignoré

            poids = 2 if champ in CHAMPS_CRITIQUES else 1
            total_poids += poids
            if valeur is not None:
                poids_remplis += poids

        score = round(poids_remplis / total_poids, 2) if total_poids > 0 else 0.0
        logger.info("Confiance pondérée — type=%s : %.2f", type_document or "INCONNU", score)
        return score

    # ── Cache ─────────────────────────────────────────────────────────────────

    def _hash(self, texte: str, type_document: Optional[str] = None) -> str:
        cle = f"{type_document or ''}::{texte}"
        return hashlib.md5(cle.encode("utf-8")).hexdigest()

    def _cache_set(self, key: str, value: dict):
        if len(self._cache) >= CACHE_MAX_SIZE:
            oldest = next(iter(self._cache))
            del self._cache[oldest]
        self._cache[key] = value

    def vider_cache(self):
        self._cache.clear()
        logger.info("Cache extraction vidé")

    def stats(self) -> dict:
        return {
            "cache_size": len(self._cache),
            "cache_max":  CACHE_MAX_SIZE,
            "max_chars":  MAX_TEXTE_CHARS,
            "llm":        llm_etat()
        }

    # ── Helpers ───────────────────────────────────────────────────────────────

    def _erreur(self, message: str, cin: str = "") -> dict:
        return {
            "json_data":        {},
            "confidence_score": 0.0,
            "cin":              cin,
            "statut":           "FAILURE",
            "erreur":           message,
            "provider":         None,
            "duree_ms":         0,
            "depuis_cache":     False
        }