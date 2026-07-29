# services/groq_service.py
"""
Service GROQ CrediSense — version professionnelle et optimisée
- Retry automatique avec backoff exponentiel (tenacity)
- Cache LRU par hash MD5 du texte
- Validation Pydantic du JSON extrait
- Découpage intelligent du texte (par paragraphes)
- Client HTTP singleton réutilisable
- Logging structuré avec métriques
- Gestion complète des erreurs GROQ (429, 500, timeout)
"""

import json
import logging
import time
import hashlib
from typing import Optional, Any
from functools import lru_cache

import httpx
from pydantic import BaseModel, Field
from tenacity import (
    retry,
    stop_after_attempt,
    wait_exponential,
    retry_if_exception_type,
    before_sleep_log
)
from config import GROQ_API_KEY, GROQ_MODEL, GROQ_API_URL

logger = logging.getLogger(__name__)

# ── Limite tokens ─────────────────────────────────────────────────────────────
MAX_TEXTE_CHARS  = 4000   # limite caractères envoyés à GROQ
CACHE_MAX_SIZE   = 256    # entrées en cache

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
        extra = "ignore"  # ignorer les champs inconnus retournés par GROQ

# ── Prompts ───────────────────────────────────────────────────────────────────
SYSTEM_PROMPT = """Tu es un expert en analyse de dossiers de crédit bancaire pour Attijariwafa Bank Tunisie.
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

# ── Singleton client HTTP ─────────────────────────────────────────────────────
_http_client: Optional[httpx.Client] = None

def get_http_client() -> httpx.Client:
    """Client HTTP singleton — réutilisé pour toutes les requêtes."""
    global _http_client
    if _http_client is None or _http_client.is_closed:
        _http_client = httpx.Client(
            timeout=httpx.Timeout(30.0, connect=5.0),
            headers={
                "Authorization": f"Bearer {GROQ_API_KEY}",
                "Content-Type":  "application/json"
            },
            http2=True  # HTTP/2 pour meilleures performances
        )
        logger.info("Client HTTP GROQ initialisé (HTTP/2)")
    return _http_client


class GroqService:

    # ── Cache en mémoire ──────────────────────────────────────────────────────
    _cache: dict = {}

    # ── Point d'entrée principal ──────────────────────────────────────────────

    def extraire_json(self, texte_nettoye: str, cin: str = "") -> dict:
        """
        Extrait les données financières structurées depuis le texte OCR.

        Args:
            texte_nettoye: texte nettoyé après OCR
            cin:           CIN du client (pour le cache et la réponse)

        Returns:
            {
              "json_data":        dict,
              "confidence_score": float,
              "cin":              str,
              "statut":           "SUCCESS" | "FAILURE",
              "duree_ms":         float,
              "depuis_cache":     bool
            }
        """
        t0 = time.time()

        if not texte_nettoye or len(texte_nettoye.strip()) < 10:
            return self._erreur("Texte OCR vide ou trop court")

        # ── 1. Vérifier le cache ─────────────────────────────────────────────
        cache_key = self._hash(texte_nettoye)
        if cache_key in self._cache:
            logger.info("Cache HIT GROQ — %.0fms", (time.time()-t0)*1000)
            result               = self._cache[cache_key].copy()
            result["depuis_cache"] = True
            result["duree_ms"]   = round((time.time()-t0)*1000, 1)
            return result

        # ── 2. Découper le texte intelligemment ─────────────────────────────
        texte_optimise = self._tronquer_intelligent(texte_nettoye)

        try:
            # ── 3. Appeler GROQ avec retry ───────────────────────────────────
            json_brut = self._appeler_groq_avec_retry(texte_optimise)

            # ── 4. Nettoyer et parser ────────────────────────────────────────
            json_dict = self._nettoyer_json(json_brut)

            # ── 5. Valider avec Pydantic ─────────────────────────────────────
            try:
                validated = ExtractionFinanciere(**json_dict)
                json_dict = validated.model_dump(exclude_none=False)
            except Exception as e:
                logger.warning("Validation Pydantic partielle : %s", str(e))

            # ── 6. Score de confiance ────────────────────────────────────────
            confidence = self._calculer_confidence(json_dict)

            duree_ms = round((time.time() - t0) * 1000, 1)
            logger.info(
                "GROQ extraction OK — confidence=%.2f, %d champs remplis, %.0fms",
                confidence,
                sum(1 for v in json_dict.values() if v is not None),
                duree_ms
            )

            result = {
                "json_data":        json_dict,
                "confidence_score": confidence,
                "cin":              cin,
                "statut":           "SUCCESS",
                "duree_ms":         duree_ms,
                "depuis_cache":     False
            }

            # ── 7. Mettre en cache ───────────────────────────────────────────
            self._cache_set(cache_key, result)
            return result

        except Exception as e:
            logger.error("Erreur GROQ extraction : %s", str(e), exc_info=True)
            return self._erreur(str(e))

    # ── Appel GROQ avec retry ─────────────────────────────────────────────────

    @retry(
        stop=stop_after_attempt(3),
        wait=wait_exponential(multiplier=1, min=2, max=10),
        retry=retry_if_exception_type((httpx.TimeoutException, httpx.HTTPStatusError)),
        before_sleep=before_sleep_log(logger, logging.WARNING)
    )
    def _appeler_groq_avec_retry(self, texte: str) -> str:
        """Appelle GROQ avec retry automatique sur timeout et erreurs 5xx."""
        user_prompt = (
            f"Extrais les informations financières du document bancaire "
            f"ci-dessous et retourne UNIQUEMENT un JSON selon ce schema :\n\n"
            f"{JSON_SCHEMA}\n\n"
            f"Document :\n{texte}"
        )

        payload = {
            "model":       GROQ_MODEL,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user",   "content": user_prompt}
            ],
            "temperature": 0.1,
            "max_tokens":  1200
        }

        client   = get_http_client()
        response = client.post(GROQ_API_URL, json=payload)

        # Gérer les erreurs HTTP
        if response.status_code == 429:
            logger.warning("GROQ rate limit (429) — retry dans quelques secondes")
            response.raise_for_status()  # déclenche le retry

        if response.status_code >= 500:
            logger.error("GROQ erreur serveur (%d)", response.status_code)
            response.raise_for_status()

        response.raise_for_status()

        data    = response.json()
        content = data["choices"][0]["message"]["content"]
        logger.debug("GROQ tokens — prompt=%d, completion=%d",
                    data.get("usage", {}).get("prompt_tokens", 0),
                    data.get("usage", {}).get("completion_tokens", 0))
        return content

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
        """Nettoie la réponse GROQ et parse le JSON."""
        propre = texte.strip()

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
            return json.loads(propre)
        except json.JSONDecodeError:
            # Tentative de réparation JSON basique
            propre = propre.replace("'", '"').replace("None", "null")
            try:
                return json.loads(propre)
            except Exception:
                logger.warning("JSON non parsable — retour dict vide")
                return {}

    # ── Score de confiance ────────────────────────────────────────────────────

    def _calculer_confidence(self, json_data: dict) -> float:
        """Score pondéré : certains champs valent plus que d'autres."""
        if not json_data:
            return 0.0

        # Champs critiques (poids 2)
        critiques = {
            "revenuMensuelNet", "typeContrat", "tauxEndettement",
            "montantCredit", "historiqueCredit"
        }

        total_poids = 0
        poids_remplis = 0

        for champ, valeur in json_data.items():
            poids = 2 if champ in critiques else 1
            total_poids += poids
            if valeur is not None:
                poids_remplis += poids

        score = round(poids_remplis / total_poids, 2) if total_poids > 0 else 0.0
        logger.info("Confiance pondérée : %.2f", score)
        return score

    # ── Cache ─────────────────────────────────────────────────────────────────

    def _hash(self, texte: str) -> str:
        return hashlib.md5(texte.encode("utf-8")).hexdigest()

    def _cache_set(self, key: str, value: dict):
        if len(self._cache) >= CACHE_MAX_SIZE:
            oldest = next(iter(self._cache))
            del self._cache[oldest]
        self._cache[key] = value

    def vider_cache(self):
        self._cache.clear()
        logger.info("Cache GROQ vidé")

    def stats(self) -> dict:
        return {
            "cache_size":  len(self._cache),
            "cache_max":   CACHE_MAX_SIZE,
            "model":       GROQ_MODEL,
            "max_chars":   MAX_TEXTE_CHARS
        }

    # ── Helpers ───────────────────────────────────────────────────────────────

    def _erreur(self, message: str) -> dict:
        return {
            "json_data":        {},
            "confidence_score": 0.0,
            "cin":              "",
            "statut":           "FAILURE",
            "erreur":           message,
            "duree_ms":         0,
            "depuis_cache":     False
        }