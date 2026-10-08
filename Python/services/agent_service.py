# services/agent_service.py
"""
Agent d'analyse crédit consommation CrediSense

Architecture en 2 couches :
  1. LLM (via services/llm_client.py) : lit le dossier, extrait les métriques,
     rédige la synthèse, les points forts / de vigilance, les recommandations
     et les conditions.
  2. Moteur de règles DÉTERMINISTE (Python) : recalcule la mensualité et le
     taux d'endettement, vérifie chaque critère réglementaire BCT, calcule la
     capacité d'emprunt et des simulations de durée. Ces chiffres ne dépendent
     pas du LLM : ils sont exacts, reproductibles et vérifiables.
     Si un critère bloquant n'est pas respecté, une décision "ELIGIBLE" du LLM
     est automatiquement ramenée à "CONDITIONNEL".

- Cache par hash MD5 du dossier (uniquement les analyses réussies)
- Validation Pydantic + réparation de JSON tronqué
- Compatible avec l'ancien format (mêmes champs + nouveaux champs ajoutés)

Configuration (.env, optionnelle) :
  CREDIT_TAUX_ANNUEL=0.10   # taux annuel indicatif (10 %) pour calculer la
                            # mensualité avec intérêts. Absent → calcul hors intérêts.
"""

import json
import logging
import os
import time
import hashlib
from datetime import date
from typing import Optional

from pydantic import BaseModel

from services.dettes_releve import detecter_dettes_releve
from services.dossier_texte import preparer_texte_dossier
from services.llm_client import chat_completion, LLMUnavailableError, etat as llm_etat

logger = logging.getLogger(__name__)

CACHE_MAX_SIZE = 128


def _max_chars_dossier() -> int:
    """Taille maximale (en caractères) du dossier envoyé au LLM — .env : AGENT_MAX_CHARS."""
    try:
        valeur = int(os.getenv("AGENT_MAX_CHARS", "12000"))
        return valeur if valeur > 0 else 12000
    except ValueError:
        return 12000

# ── Règles réglementaires (BCT Tunisie — crédit à la consommation) ────────────
DTI_ACCEPTABLE      = 30.0   # % — en dessous : acceptable
DTI_MAX             = 35.0   # % — au-dessus : refus
MULTIPLE_SALAIRE    = 5      # montant max = 5 × salaire mensuel net
DUREE_MAX_MOIS      = 84     # 7 ans
ANCIENNETE_MIN_MOIS = 6
AGE_MAX_FIN_CREDIT  = 70
DUREES_SIMULEES     = [12, 24, 36, 48, 60, 84]


# À incrémenter quand les règles changent : fait partie de la clé de cache, pour qu'une
# ancienne analyse (faite avec d'anciennes règles ou un autre taux) ne soit jamais resservie.
RULES_VERSION = "2026-10-b"


def _taux_annuel() -> Optional[float]:
    """
    Taux d'intérêt annuel du crédit, en décimal (0.10 = 10 %) — .env : CREDIT_TAUX_ANNUEL.
    Obligatoire : sans taux valide, aucune mensualité ni taux d'endettement n'est calculé
    (la décision est « À COMPLÉTER »). Accepte aussi « 10 » ou « 10,5 » (pourcentage).
    Renvoie None si absent, illisible, ou hors de ]0 ; 50 %].
    """
    valeur = os.getenv("CREDIT_TAUX_ANNUEL", "").strip().replace(",", ".")
    try:
        taux = float(valeur)
    except ValueError:
        return None
    if 1 < taux <= 100:          # saisi en pourcentage (10 pour 10 %)
        taux /= 100
    return taux if 0 < taux <= 0.5 else None


# ── Schéma Pydantic ───────────────────────────────────────────────────────────

class MetriquesFinancieres(BaseModel):
    dti:                Optional[float] = None
    monthlyIncome:      Optional[float] = None
    requestedAmount:    Optional[float] = None
    duration:           Optional[int]   = None
    monthlyPayment:     Optional[float] = None
    existingDebts:      Optional[float] = None
    contractType:       Optional[str]   = None
    employmentStartDate: Optional[str]  = None
    paymentIncidents:   Optional[int]   = None
    clientAge:          Optional[int]   = None

    class Config:
        extra = "ignore"

class CreditAnalysisResult(BaseModel):
    eligibility:      str                            = "INDETERMINE"
    eligibilityScore: Optional[int]                  = None
    summary:          Optional[str]                  = None
    financialMetrics: Optional[MetriquesFinancieres] = None
    strengths:        list                           = []
    weaknesses:       list                           = []
    risks:            list                           = []
    recommendedPlan:  list                           = []
    conditions:       list                           = []
    documentSources:  list                           = []
    avertissements:   list                           = []   # ex : dossier raccourci avant analyse
    rawExplanation:   Optional[str]                  = None
    creditType:       str                            = "CONSOMMATION"

    class Config:
        extra = "ignore"

# ── System Prompt ─────────────────────────────────────────────────────────────

SYSTEM_PROMPT = """Tu es un expert senior en analyse de crédit à la consommation pour Attijari Bank Tunisie.
Tu rédiges une note d'analyse destinée à un agent bancaire : elle doit être claire, argumentée et actionnable.

MISSION : Analyser le dossier bancaire fourni et retourner UNIQUEMENT un JSON valide.

CRITÈRES RÉGLEMENTAIRES BCT TUNISIE :
- DTI (taux d'endettement) : ACCEPTABLE < 30% | RISQUE 30-35% | REFUS > 35%
- Montant maximum accordé : 5 × salaire mensuel net
- Durée maximale : 84 mois (7 ans)
- Ancienneté emploi minimum : 6 mois
- Âge à la fin du crédit : ≤ 70 ans
- Incidents de paiement > 0 : risque élevé
- CDI / fonctionnaire : favorable | CDD : risque modéré | Indépendant : vérification bilan

LOGIQUE DE SCORING (0-100) :
- 80-100 : ELIGIBLE (dossier solide)
- 60-79  : ELIGIBLE (dossier acceptable)
- 40-59  : CONDITIONNEL (garanties supplémentaires requises)
- 0-39   : REFUS (critères non satisfaits)

FORMAT DES MONTANTS :
- Le dinar tunisien a 3 décimales : "4 800,000" = 4800 DT. Dans le JSON, écris les montants
  comme des nombres (4800.0), jamais 4800000.

SOURCES (règle stricte) :
- Le revenu mensuel net provient de la FICHE DE PAIE ("Net à payer"), pas de l'attestation d'emploi.
- Le type de contrat et la date d'embauche proviennent de l'attestation d'emploi ou de la fiche de paie.
- Les dettes existantes et incidents proviennent du relevé bancaire.
- Montant demandé et durée : "Formulaire de demande".

FORMAT DE RÉPONSE — JSON STRICT UNIQUEMENT :
{
  "eligibility": "ELIGIBLE" | "CONDITIONNEL" | "REFUS",
  "eligibilityScore": <entier 0-100>,
  "summary": "<synthèse de la décision en 2 à 3 phrases, compréhensible par un non-spécialiste>",
  "financialMetrics": {
    "dti": <float en %>,
    "monthlyIncome": <float en DT>,
    "requestedAmount": <float en DT>,
    "duration": <entier en mois>,
    "monthlyPayment": <float en DT>,
    "existingDebts": <float en DT, mensualités de crédits en cours>,
    "contractType": "CDI" | "CDD" | "FONCTIONNAIRE" | "INDEPENDANT" | "RETRAITE" | null,
    "employmentStartDate": "<JJ/MM/AAAA ou null>",
    "paymentIncidents": <entier ou null>,
    "clientAge": <entier ou null>
  },
  "strengths": [
    { "title": "<point fort court>", "detail": "<explication chiffrée en 1 phrase>", "source": "<document>" }
  ],
  "weaknesses": [
    { "title": "<point de vigilance court>", "detail": "<explication en 1 phrase>", "source": "<document>" }
  ],
  "risks": [
    { "level": "HIGH" | "MEDIUM" | "LOW", "description": "<risque précis>", "source": "<document>" }
  ],
  "recommendedPlan": [
    {
      "priority": <entier 1-5, 1 = le plus important>,
      "action": "<action concrète pour l'agent bancaire>",
      "rationale": "<pourquoi, en 1 à 2 phrases, avec chiffres si possible>",
      "impact": "<effet attendu de l'action sur le dossier>",
      "source": "<document>"
    }
  ],
  "conditions": [
    "<condition ou pièce complémentaire à obtenir avant décaissement>"
  ],
  "documentSources": [
    { "field": "<champ extrait>", "value": "<valeur avec unité>", "foundIn": "<document source>" }
  ],
  "rawExplanation": "<analyse détaillée en prose, 6 à 10 phrases>"
}

CONTENU ATTENDU :
- strengths : 3 à 5 points forts réels du dossier.
- weaknesses : 2 à 4 points de vigilance (même un bon dossier en a : ancienneté, absence de garantie, épargne...).
- recommendedPlan : 3 à 5 actions concrètes et différentes, triées par priorité. Exemples :
  vérifier la domiciliation du salaire, demander une assurance décès-invalidité, proposer une
  durée optimisée, demander les 3 derniers relevés, vérifier la centrale des risques BCT.
- conditions : 2 à 4 conditions ou pièces à obtenir avant décaissement.
- rawExplanation : 6 à 10 phrases : situation du client, capacité de remboursement,
  respect de chaque critère BCT, risques, recommandation finale.

RÈGLES ABSOLUES :
- Retourner UNIQUEMENT le JSON — aucun texte avant ou après
- Tous les montants en DT (Dinar Tunisien)
- rawExplanation et summary : UNIQUEMENT du texte en prose, JAMAIS de JSON, de markdown, ni de balises ```
- Ne jamais inventer une donnée absente : utiliser null et le signaler dans weaknesses
- risks = [] si aucun risque identifié
"""

# ── Moteur de règles déterministe ─────────────────────────────────────────────

def calculer_mensualite(montant: float, duree_mois: int, taux_annuel: Optional[float]) -> float:
    """Mensualité constante. Sans taux : montant / durée (hors intérêts)."""
    if not montant or not duree_mois:
        return 0.0
    if not taux_annuel:
        return montant / duree_mois
    r = taux_annuel / 12
    return montant * r / (1 - (1 + r) ** (-duree_mois))


def montant_finançable(mensualite_max: float, duree_mois: int, taux_annuel: Optional[float]) -> float:
    """Montant maximum empruntable pour une mensualité donnée."""
    if mensualite_max <= 0 or not duree_mois:
        return 0.0
    if not taux_annuel:
        return mensualite_max * duree_mois
    r = taux_annuel / 12
    return mensualite_max * (1 - (1 + r) ** (-duree_mois)) / r


def anciennete_en_mois(date_embauche: Optional[str]) -> Optional[int]:
    """'02/09/2022' → nombre de mois jusqu'à aujourd'hui."""
    if not date_embauche:
        return None
    for sep in ("/", "-", "."):
        parties = str(date_embauche).strip().split(sep)
        if len(parties) == 3:
            try:
                j, m, a = (int(p) for p in parties)
                if a < 100:
                    a += 2000
                debut = date(a, m, j)
                auj = date.today()
                return (auj.year - debut.year) * 12 + (auj.month - debut.month) - (auj.day < debut.day)
            except ValueError:
                continue
    return None


def _dt(valeur: float, decimales: int = 3) -> str:
    """Format tunisien : 24000 → '24 000,000 DT'."""
    texte = f"{valeur:,.{decimales}f}".replace(",", " ").replace(".", ",")
    return f"{texte} DT"


def _num(valeur) -> Optional[float]:
    try:
        return float(valeur) if valeur is not None else None
    except (TypeError, ValueError):
        return None


def _check(critere: str, statut: str, valeur: str, seuil: str, explication: str, bloquant: bool) -> dict:
    return {
        "criterion":   critere,
        "status":      statut,          # OK | ATTENTION | KO | A_VERIFIER
        "value":       valeur,
        "threshold":   seuil,
        "explanation": explication,
        "blocking":    bloquant,
    }


def appliquer_regles(metrics: dict, dettes_connues: bool = True) -> dict:
    """
    Recalcule mensualité / DTI et vérifie chaque critère réglementaire.

    Règle de fond : on NE CALCULE JAMAIS sur une donnée inconnue.
      - sans taux d'intérêt valide (CREDIT_TAUX_ANNUEL) : ni mensualité, ni taux d'endettement,
        ni capacité d'emprunt, ni simulation (avant : calcul « hors intérêts », trop optimiste) ;
      - dettes existantes inconnues (`dettes_connues=False`) : pas de taux d'endettement
        (avant : une dette inconnue valait 0 et améliorait le dossier).

    Retourne : {"metrics": ..., "regulatoryChecks": [...], "capacity": {...},
                "simulations": [...], "calculationNote": str, "taux": float | None}
    """
    taux    = _taux_annuel()
    revenu  = _num(metrics.get("monthlyIncome"))
    montant = _num(metrics.get("requestedAmount"))
    duree   = int(_num(metrics.get("duration")) or 0) or None
    dettes  = _num(metrics.get("existingDebts")) or 0.0

    metrics = dict(metrics)
    checks  = []

    # ── Mensualité et DTI recalculés (jamais ceux estimés par le LLM) ─────────
    mensualite = None
    dti        = None
    if taux is not None and montant and duree:
        mensualite = round(calculer_mensualite(montant, duree, taux), 3)
    metrics["monthlyPayment"] = mensualite
    if revenu and mensualite is not None and dettes_connues:
        dti = round((mensualite + dettes) / revenu * 100, 2)
    metrics["dti"] = dti

    # 0. Taux d'intérêt appliqué
    if taux is None:
        # Texte lu par l'AGENT : pas de nom de variable ni de jargon technique. Le réglage
        # (CREDIT_TAUX_ANNUEL) se fait côté serveur, par l'administrateur : voir le journal.
        logger.warning("Taux d'intérêt absent ou invalide : à renseigner dans CREDIT_TAUX_ANNUEL (ai.env)")
        checks.append(_check("Taux d'intérêt appliqué", "A_VERIFIER", "non renseigné", "taux annuel obligatoire",
                             "Le taux d'intérêt du crédit n'est pas encore renseigné : l'administrateur doit "
                             "le paramétrer. Tant qu'il manque, aucune mensualité ne peut être calculée.", True))
    else:
        checks.append(_check("Taux d'intérêt appliqué", "OK", f"{taux * 100:.2f} % par an", "taux renseigné",
                             "Taux annuel utilisé pour toutes les mensualités et simulations.", False))

    # 1. Taux d'endettement
    if dti is None:
        raisons = []
        if taux is None:
            raisons.append("taux d'intérêt non renseigné")
        if not revenu:
            raisons.append("revenu mensuel net introuvable")
        if not (montant and duree):
            raisons.append("montant ou durée de la demande manquant")
        if not dettes_connues:
            raisons.append("dettes existantes inconnues")
        checks.append(_check("Taux d'endettement", "A_VERIFIER", "inconnu", f"< {DTI_ACCEPTABLE:.0f} %",
                             "Taux d'endettement non calculable : " + ", ".join(raisons or ["donnée manquante"]) + ".",
                             True))
    elif dti < DTI_ACCEPTABLE:
        checks.append(_check("Taux d'endettement", "OK", f"{dti:.2f} %", f"< {DTI_ACCEPTABLE:.0f} %",
                             f"Les charges de crédit représentent {dti:.2f} % du revenu, sous le seuil de {DTI_ACCEPTABLE:.0f} %.", True))
    elif dti <= DTI_MAX:
        checks.append(_check("Taux d'endettement", "ATTENTION", f"{dti:.2f} %", f"< {DTI_ACCEPTABLE:.0f} %",
                             f"Zone de risque ({DTI_ACCEPTABLE:.0f}-{DTI_MAX:.0f} %) : garanties ou durée plus longue recommandées.", True))
    else:
        checks.append(_check("Taux d'endettement", "KO", f"{dti:.2f} %", f"≤ {DTI_MAX:.0f} %",
                             f"Au-delà du maximum réglementaire de {DTI_MAX:.0f} %.", True))

    # 1 bis. Dettes existantes : connues (relevé, IA) ou à confirmer
    if dettes_connues:
        checks.append(_check("Dettes existantes", "OK", f"{_dt(dettes)} / mois", "connues",
                             ("Échéances de crédit en cours : " + _dt(dettes) + " par mois.") if dettes
                             else "Aucune échéance de crédit en cours.", False))
    else:
        checks.append(_check("Dettes existantes", "A_VERIFIER", "inconnues", "à confirmer",
                             "Aucun relevé bancaire dans le dossier : impossible de savoir si le client "
                             "a des crédits en cours. À confirmer (relevé des 3 derniers mois, "
                             "centrale des risques).", True))

    # 2. Plafond 5 × salaire
    plafond = round(MULTIPLE_SALAIRE * revenu, 3) if revenu else None
    if plafond is None or not montant:
        checks.append(_check("Plafond du montant", "A_VERIFIER", "inconnu", f"≤ {MULTIPLE_SALAIRE} × salaire",
                             "Montant demandé ou revenu manquant.", True))
    elif montant <= plafond:
        checks.append(_check("Plafond du montant", "OK", _dt(montant, 0), f"≤ {_dt(plafond, 0)}",
                             f"Le montant demandé représente {montant / revenu:.1f} fois le salaire (maximum {MULTIPLE_SALAIRE}).", True))
    else:
        checks.append(_check("Plafond du montant", "KO", _dt(montant, 0), f"≤ {_dt(plafond, 0)}",
                             f"Le montant dépasse {MULTIPLE_SALAIRE} fois le salaire mensuel net.", True))

    # 3. Durée
    if not duree:
        checks.append(_check("Durée du crédit", "A_VERIFIER", "inconnue", f"≤ {DUREE_MAX_MOIS} mois",
                             "Durée non renseignée.", True))
    else:
        ok = duree <= DUREE_MAX_MOIS
        checks.append(_check("Durée du crédit", "OK" if ok else "KO", f"{duree} mois", f"≤ {DUREE_MAX_MOIS} mois",
                             "Durée conforme." if ok else "Durée supérieure au maximum réglementaire de 7 ans.", True))

    # 4. Ancienneté
    anciennete = anciennete_en_mois(metrics.get("employmentStartDate"))
    if anciennete is None:
        checks.append(_check("Ancienneté dans l'emploi", "A_VERIFIER", "inconnue", f"≥ {ANCIENNETE_MIN_MOIS} mois",
                             "Date d'embauche non trouvée : à confirmer avec l'attestation d'emploi.", True))
    else:
        ok = anciennete >= ANCIENNETE_MIN_MOIS
        annees, mois = divmod(anciennete, 12)
        checks.append(_check("Ancienneté dans l'emploi", "OK" if ok else "KO",
                             f"{annees} an(s) {mois} mois", f"≥ {ANCIENNETE_MIN_MOIS} mois",
                             "Stabilité professionnelle suffisante." if ok else "Ancienneté insuffisante.", True))

    # 5. Type de contrat
    contrat = (metrics.get("contractType") or "").upper()
    if contrat in ("CDI", "FONCTIONNAIRE"):
        checks.append(_check("Type de contrat", "OK", contrat, "CDI / fonctionnaire",
                             "Contrat stable, favorable au remboursement.", False))
    elif contrat in ("CDD", "INDEPENDANT", "RETRAITE"):
        checks.append(_check("Type de contrat", "ATTENTION", contrat, "CDI / fonctionnaire",
                             "Contrat moins stable : vérifier la date de fin de contrat ou les bilans.", False))
    else:
        checks.append(_check("Type de contrat", "A_VERIFIER", "inconnu", "CDI / fonctionnaire",
                             "Type de contrat non identifié dans les documents.", False))

    # 6. Incidents de paiement
    incidents = metrics.get("paymentIncidents")
    if incidents is None:
        checks.append(_check("Incidents de paiement", "A_VERIFIER", "inconnu", "0",
                             "À vérifier auprès de la centrale des risques BCT.", False))
    else:
        ok = int(incidents) == 0
        checks.append(_check("Incidents de paiement", "OK" if ok else "KO", str(incidents), "0",
                             "Aucun incident relevé." if ok else "Incidents de paiement détectés : risque élevé.", not ok))

    # 7. Âge en fin de crédit
    age = metrics.get("clientAge")
    if age is not None and duree:
        age_fin = int(age) + duree / 12
        ok = age_fin <= AGE_MAX_FIN_CREDIT
        checks.append(_check("Âge en fin de crédit", "OK" if ok else "KO", f"{age_fin:.0f} ans",
                             f"≤ {AGE_MAX_FIN_CREDIT} ans",
                             "Conforme." if ok else "Le client dépasserait l'âge maximum en fin de crédit.", True))

    # ── Capacité d'emprunt ───────────────────────────────────────────────────
    capacity = {}
    if revenu and taux is not None and dettes_connues:
        mens_max = max(0.0, DTI_ACCEPTABLE / 100 * revenu - dettes)
        duree_ref = duree or 60
        montant_max = montant_finançable(mens_max, duree_ref, taux)
        if plafond is not None:
            montant_max = min(montant_max, plafond)
        capacity = {
            "maxMonthlyPayment":   round(mens_max, 3),
            "remainingMonthly":    round(mens_max - (mensualite or 0), 3),
            "maxAmountForDuration": round(montant_max, 3),
            "referenceDuration":   duree_ref,
            "salaryCap":           plafond,
            "explanation": (
                f"Avec un revenu de {_dt(revenu)} et {_dt(dettes)} de dettes en cours, "
                f"la mensualité maximale à {DTI_ACCEPTABLE:.0f} % d'endettement est de {_dt(mens_max)}. "
                f"Sur {duree_ref} mois, le client peut emprunter jusqu'à {_dt(montant_max)}."
            ),
        }

    # ── Simulations de durée ─────────────────────────────────────────────────
    simulations = []
    if montant and revenu and taux is not None and dettes_connues:
        durees = sorted(set(DUREES_SIMULEES + ([duree] if duree else [])))
        for n in durees:
            if n > DUREE_MAX_MOIS:
                continue
            m = calculer_mensualite(montant, n, taux)
            d = (m + dettes) / revenu * 100
            simulations.append({
                "duration":       n,
                "monthlyPayment": round(m, 3),
                "dti":            round(d, 2),
                "totalCost":      round(m * n, 3),
                "status":         "OK" if d < DTI_ACCEPTABLE else ("ATTENTION" if d <= DTI_MAX else "KO"),
                "isRequested":    n == duree,
            })

    note = (f"Mensualités calculées avec un taux annuel de {taux * 100:.2f} % (configuration du service)."
            if taux is not None else
            "Taux d'intérêt non renseigné : mensualités et taux d'endettement non calculés.")

    return {
        "metrics":          metrics,
        "regulatoryChecks": checks,
        "capacity":         capacity,
        "simulations":      simulations,
        "calculationNote":  note,
        "taux":             taux,
    }


# ── Propositions d'ajustement (dossier CONDITIONNEL) ─────────────────────────
PAS_MONTANT   = 100     # DT : les montants proposés sont arrondis au pas inférieur
MONTANT_MIN   = 500     # DT : en dessous, une offre n'a plus de sens
PAS_DUREE     = 6       # mois : les durées proposées sont des multiples de 6

# Critères qu'un changement de montant ou de durée peut améliorer ; tout autre critère non conforme
# (ancienneté, contrat, incidents…) reste à traiter autrement et est signalé tel quel.
CRITERES_AJUSTABLES = {"Taux d'endettement", "Plafond du montant", "Durée du crédit", "Âge en fin de crédit"}


def _arrondi_inferieur(valeur: float) -> float:
    return float(int(valeur // PAS_MONTANT) * PAS_MONTANT)


def proposer_ajustements(metrics: dict, taux: Optional[float], dettes_connues: bool, checks: list) -> dict:
    """
    Pour un dossier CONDITIONNEL : montants et durées qui respectent le seuil d'endettement,
    le plafond de 5 × salaire, la durée maximale et l'âge maximal en fin de crédit.

    Entièrement déterministe (aucun LLM) : chaque offre est recalculée avec les mêmes formules que
    la décision. Une offre n'est jamais proposée sur une donnée inconnue.

    Retourne {"applicable": bool, "message": str, "offers": [...], "unresolved": [critères non conformes
    qu'un changement de montant ou de durée ne règle pas]}. Chaque offre :
    {"kind", "label", "amount", "duration", "monthlyPayment", "dti", "totalCost", "explanation"}.
    """
    revenu  = _num(metrics.get("monthlyIncome"))
    montant = _num(metrics.get("requestedAmount"))
    duree   = int(_num(metrics.get("duration")) or 0) or None
    dettes  = _num(metrics.get("existingDebts")) or 0.0
    age     = _num(metrics.get("clientAge"))

    non_resolus = [c["criterion"] for c in checks
                   if c.get("status") in ("KO", "ATTENTION", "A_VERIFIER")
                   and c.get("criterion") not in CRITERES_AJUSTABLES
                   and c.get("criterion") not in ("Taux d'intérêt appliqué", "Dettes existantes")]

    def refus(message: str) -> dict:
        return {"applicable": False, "message": message, "offers": [], "unresolved": non_resolus}

    if taux is None or not (revenu and montant and duree) or not dettes_connues:
        return refus("Pas assez d'informations fiables (taux, revenu, demande ou dettes) pour proposer un ajustement.")

    plafond = MULTIPLE_SALAIRE * revenu
    mens_max = DTI_ACCEPTABLE / 100 * revenu - dettes
    mensualite = calculer_mensualite(montant, duree, taux)
    dti = (mensualite + dettes) / revenu * 100

    if dti < DTI_ACCEPTABLE and montant <= plafond and duree <= DUREE_MAX_MOIS:
        return refus("L'endettement, le plafond et la durée sont déjà respectés : changer le montant ou la durée "
                     "ne suffit pas. Le caractère conditionnel vient d'autres critères, à traiter avec le client.")
    if mens_max <= 0:
        return refus("Les dettes en cours absorbent déjà la capacité de remboursement : aucun montant ne passe "
                     "sous le seuil d'endettement.")

    # Durée la plus longue possible : plafond réglementaire, et âge en fin de crédit
    duree_max = DUREE_MAX_MOIS
    if age is not None:
        duree_max = min(duree_max, int((AGE_MAX_FIN_CREDIT - age) * 12))
    duree_max = (duree_max // PAS_DUREE) * PAS_DUREE if duree_max < DUREE_MAX_MOIS else duree_max

    def offre(kind: str, label: str, m: float, n: int, explication: str) -> Optional[dict]:
        mens = calculer_mensualite(m, n, taux)
        d = (mens + dettes) / revenu * 100
        # Revérification complète : on ne propose que ce qui passe tous les contrôles ajustables
        if m < MONTANT_MIN or m > plafond or n > duree_max or d >= DTI_ACCEPTABLE:
            return None
        return {"kind": kind, "label": label, "amount": round(m, 3), "duration": n,
                "monthlyPayment": round(mens, 3), "dti": round(d, 2), "totalCost": round(mens * n, 3),
                "explanation": explication}

    offres = []

    # A. Même durée, montant réduit
    montant_a = _arrondi_inferieur(min(montant_finançable(mens_max, duree, taux), plafond, montant))
    a = offre("MONTANT_REDUIT", "Montant réduit, même durée", montant_a, duree,
              f"Sur {duree} mois, {_dt(montant_a, 0)} est le montant le plus élevé qui garde l'endettement "
              f"sous {DTI_ACCEPTABLE:.0f} %.") if duree <= duree_max and montant_a < montant else None
    if a:
        offres.append(a)

    # B. Même montant, durée allongée (la plus courte qui convient)
    b = None
    if montant <= plafond:
        for n in [n for n in range(duree + 1, duree_max + 1) if n % PAS_DUREE == 0 or n == duree_max]:
            b = offre("DUREE_ALLONGEE", "Même montant, durée allongée", montant, n,
                      f"En allongeant à {n} mois, le montant demandé passe sous {DTI_ACCEPTABLE:.0f} % "
                      f"d'endettement, au prix d'un coût total plus élevé.")
            if b:
                break
    if b:
        offres.append(b)

    # C. À défaut, durée maximale et montant maximal
    if not b and duree_max > duree:
        montant_c = _arrondi_inferieur(min(montant_finançable(mens_max, duree_max, taux), plafond, montant))
        c = offre("COMBINE", "Durée maximale et montant ajusté", montant_c, duree_max,
                  f"Sur {duree_max} mois, {_dt(montant_c, 0)} est le montant le plus élevé acceptable.")
        if c and (not a or c["amount"] > a["amount"]):
            offres.append(c)

    if not offres:
        return refus("Aucun montant d'au moins " + _dt(MONTANT_MIN, 0) + " ne respecte à la fois le seuil "
                     "d'endettement, le plafond et la durée autorisée.")

    message = "Propositions indicatives, calculées par les règles, sous réserve de validation par l'agent."
    if non_resolus:
        message += (" Attention : ces critères ne sont pas réglés par un changement de montant ou de durée : "
                    + ", ".join(non_resolus) + ".")
    return {"applicable": True, "message": message, "offers": offres, "unresolved": non_resolus}


def donnees_manquantes(metrics: dict, taux: Optional[float], dettes_connues: bool, checks: list) -> list[str]:
    """
    Liste lisible de ce qui manque pour pouvoir rendre une décision, avec où le trouver.
    Vide si tout est là. Sert à la décision « À COMPLÉTER » et à l'écran de l'agent.
    """
    manquantes = []
    if not _num(metrics.get("monthlyIncome")):
        manquantes.append("Revenu mensuel net — fiche de paie (« net à payer »)")
    if not _num(metrics.get("requestedAmount")) or not _num(metrics.get("duration")):
        manquantes.append("Montant et durée demandés — formulaire de demande de crédit")
    if taux is None:
        manquantes.append("Taux d'intérêt annuel — à renseigner par l'administrateur (réglage du service d'analyse)")
    if not dettes_connues:
        manquantes.append("Dettes existantes — relevé bancaire des 3 derniers mois")
    if any(c["criterion"] == "Ancienneté dans l'emploi" and c["status"] == "A_VERIFIER" for c in checks):
        manquantes.append("Date d'embauche — attestation de travail")
    return manquantes


# ── Classe principale ─────────────────────────────────────────────────────────

class AgentService:

    _cache: dict = {}

    def __init__(self):
        logger.info("AgentService consommation initialisé")

    # ── Point d'entrée principal ──────────────────────────────────────────────
    def analyser_consommation(self, document_text: str) -> dict:
        t0 = time.time()
        logger.info("Analyse consommation — %d chars", len(document_text))

        # Cache — la clé inclut la version des règles et le taux : changer l'un des deux
        # ne doit jamais ressortir une ancienne analyse (par exemple « À COMPLÉTER » faite
        # avant que le taux ne soit configuré)
        cle_brute = f"{RULES_VERSION}|{_taux_annuel()}|{_max_chars_dossier()}|{document_text}"
        cache_key = hashlib.md5(cle_brute.encode()).hexdigest()
        if cache_key in self._cache:
            logger.info("Cache HIT agent consommation")
            result = self._cache[cache_key].copy()
            result["depuis_cache"] = True
            result["duree_ms"]     = round((time.time()-t0)*1000, 1)
            return result

        try:
            # Répartit le budget de caractères entre les documents (plus de coupe
            # brutale qui supprimait les derniers documents sans rien dire)
            texte_dossier, avertissements = preparer_texte_dossier(
                document_text, _max_chars_dossier()
            )
            if avertissements:
                logger.warning("Dossier raccourci avant analyse (%d → %d chars) : %s",
                               len(document_text), len(texte_dossier), " | ".join(avertissements))

            reponse     = self._appeler_llm(texte_dossier)
            result_dict = self._parser_resultat(reponse.content)
            result_dict["avertissements"] = avertissements

            # Validation Pydantic
            try:
                validated   = CreditAnalysisResult(**result_dict)
                result_dict = validated.model_dump()
            except Exception as e:
                logger.warning("Validation partielle : %s", str(e))

            # ── Couche déterministe : calculs et critères réglementaires ─────
            metrics = result_dict.get("financialMetrics") or {}
            if isinstance(metrics, dict):
                # Dettes : lues par l'IA ET détectées par règle dans le relevé (texte COMPLET,
                # avant tout raccourcissement). Une dette inconnue n'est jamais comptée comme nulle.
                detection = detecter_dettes_releve(document_text)
                avert = result_dict.setdefault("avertissements", [])
                metrics, dettes_connues, notes = self._determiner_dettes(metrics, detection, avert)

                regles = appliquer_regles(metrics, dettes_connues)
                result_dict["financialMetrics"]    = regles["metrics"]
                result_dict["regulatoryChecks"]    = regles["regulatoryChecks"]
                result_dict["capacity"]            = regles["capacity"]
                result_dict["simulations"]         = regles["simulations"]
                result_dict["calculationNote"]     = " ".join([regles["calculationNote"], *notes])
                result_dict["tauxAnnuelApplique"]  = regles["taux"]
                result_dict["donneesManquantes"]   = donnees_manquantes(
                    regles["metrics"], regles["taux"], dettes_connues, regles["regulatoryChecks"])
                self._verifier_coherence(result_dict)
                self._appliquer_donnees_manquantes(result_dict)
                if result_dict.get("eligibility") == "CONDITIONNEL":
                    result_dict["adjustedOffers"] = proposer_ajustements(
                        regles["metrics"], regles["taux"], dettes_connues, regles["regulatoryChecks"])

            # Génère documentSources si absent
            if not result_dict.get('documentSources'):
                result_dict['documentSources'] = []
                metrics = result_dict.get('financialMetrics') or {}
                if isinstance(metrics, dict):
                    sources = [
                        ('monthlyIncome',   'Revenu mensuel net',       '{:,.3f} DT', 'Fiche de paie'),
                        ('dti',             "Taux d'endettement (DTI)", '{}%',        'Calcul automatique'),
                        ('requestedAmount', 'Montant demandé',          '{:,.3f} DT', 'Formulaire de demande'),
                        ('monthlyPayment',  'Mensualité estimée',       '{:,.3f} DT', 'Calcul automatique'),
                        ('duration',        'Durée',                    '{} mois',    'Formulaire de demande'),
                        ('existingDebts',   'Dettes existantes',        '{:,.3f} DT', 'Relevé bancaire'),
                    ]
                    for key, field, fmt, found_in in sources:
                        val = metrics.get(key)
                        if val is not None:
                            try:
                                result_dict['documentSources'].append({
                                    'field':   field,
                                    'value':   fmt.format(val).replace(",", " "),
                                    'foundIn': found_in
                                })
                            except Exception:
                                pass

            duree_ms = round((time.time()-t0)*1000, 1)
            logger.info(
                "Analyse OK — provider=%s, eligibility=%s, score=%s, %.0fms",
                reponse.provider,
                result_dict.get("eligibility"),
                result_dict.get("eligibilityScore"),
                duree_ms
            )

            analyse_complete = result_dict.get("eligibility") != "INDETERMINE"

            result_dict["statut"]       = "SUCCESS" if analyse_complete else "FAILURE"
            result_dict["provider"]     = reponse.provider
            # Traçabilité : avec quelle version des règles (seuils, formules) cette décision a été rendue.
            # Le journal d'audit la conserve ; elle change à chaque modification des règles.
            result_dict["versionRegles"] = RULES_VERSION
            result_dict["duree_ms"]     = duree_ms
            result_dict["depuis_cache"] = False

            # Mise en cache — uniquement les analyses complètes
            if analyse_complete:
                if len(self._cache) >= CACHE_MAX_SIZE:
                    oldest = next(iter(self._cache))
                    del self._cache[oldest]
                self._cache[cache_key] = result_dict

            return result_dict

        except LLMUnavailableError as e:
            logger.error("Analyse consommation impossible : %s", str(e))
            return self._resultat_echec(
                "Le service d'analyse IA est momentanément saturé. Veuillez réessayer dans quelques instants.",
                t0
            )

        except Exception as e:
            logger.error("Erreur analyse consommation : %s", str(e), exc_info=True)
            return self._resultat_echec(f"Erreur analyse : {str(e)}", t0)

    # ── Dettes existantes : IA + détection par règle ──────────────────────────
    def _determiner_dettes(self, metrics: dict, detection: dict, avertissements: list) -> tuple[dict, bool, list]:
        """
        Renvoie (metrics, dettes_connues, notes). Priorité à la prudence :
          - IA et relevé donnent chacun une valeur : on garde la plus élevée (et on signale un écart) ;
          - relevé présent mais aucune échéance trouvée : dettes nulles (à confirmer) ;
          - aucun relevé et aucune valeur crédible : dettes INCONNUES (pas de taux d'endettement).
        Un « 0 » donné par l'IA sans aucun relevé dans le dossier n'est pas une preuve.
        """
        metrics = dict(metrics)
        notes: list[str] = []
        lues      = _num(metrics.get("existingDebts"))
        detectees = detection["mensualite"]
        # « relevé » = un relevé présent ET lisible : seul cas où l'absence d'échéance veut dire quelque chose
        releve    = detection["releve_present"] and detection.get("exploitable", True)

        if detection["releve_present"] and not releve:
            avertissements.append(
                "Le relevé bancaire n'a pas pu être lu correctement (aucune opération datée reconnue) : "
                "les dettes existantes n'ont pas pu être vérifiées.")

        if lues is not None and lues == 0 and not releve:
            lues = None                      # « 0 » sans relevé lisible : pas fiable

        if lues is not None and detectees is not None:
            retenues = max(lues, detectees)
            if abs(lues - detectees) > 0.15 * retenues:
                avertissements.append(
                    f"Dettes existantes : l'IA a lu {_dt(lues)} par mois, le relevé bancaire en montre "
                    f"{_dt(detectees)} : la valeur la plus élevée ({_dt(retenues)}) est retenue. À vérifier.")
            metrics["existingDebts"] = retenues
            return metrics, True, notes

        if detectees is not None:
            metrics["existingDebts"] = detectees
            approx = " (estimation : dates illisibles)" if detection["approximatif"] else ""
            notes.append(f"Dettes existantes : {_dt(detectees)} par mois détectées dans le relevé bancaire{approx}.")
            return metrics, True, notes

        if lues is not None:
            metrics["existingDebts"] = lues
            return metrics, True, notes

        if releve:
            metrics["existingDebts"] = 0.0
            notes.append("Dettes existantes : aucune échéance de crédit détectée dans le relevé bancaire "
                         "(à confirmer auprès de la centrale des risques).")
            return metrics, True, notes

        metrics["existingDebts"] = None
        return metrics, False, notes

    # ── Données manquantes : jamais « éligible » sur un dossier incomplet ─────
    def _appliquer_donnees_manquantes(self, result_dict: dict) -> None:
        """
        Si des informations indispensables manquent, une décision favorable du LLM
        (ELIGIBLE ou CONDITIONNEL) devient « A_COMPLETER » : on ne peut pas décider sans elles.
        Un REFUS fondé sur un critère connu reste un REFUS.
        """
        manquantes = result_dict.get("donneesManquantes") or []
        if not manquantes or result_dict.get("eligibility") not in ("ELIGIBLE", "CONDITIONNEL"):
            return

        logger.warning("Décision LLM %s remplacée par A_COMPLETER (données manquantes : %s)",
                       result_dict.get("eligibility"), "; ".join(manquantes))
        noms = "; ".join(m.split(" — ")[0] for m in manquantes)

        result_dict["analysePreliminaire"] = result_dict.get("rawExplanation") or ""
        result_dict["eligibility"]         = "A_COMPLETER"
        result_dict["eligibilityScore"]    = min(int(result_dict.get("eligibilityScore") or 0), 59)
        result_dict["scoreProvisoire"]     = True
        # `summary` s'adresse à l'AGENT : il voit tout. `rawExplanation` peut partir par e-mail
        # au CLIENT : il ne doit contenir que des pièces que le client peut fournir, jamais un
        # réglage technique du service (taux d'intérêt à configurer, etc.).
        pieces_client = [m for m in manquantes if not m.startswith("Taux d'intérêt")]
        result_dict["summary"] = (
            f"Décision impossible pour l'instant : des informations indispensables manquent ({noms}). "
            f"Le détail ci-dessous est une analyse préliminaire.")
        result_dict["rawExplanation"] = (
            "Pour finaliser l'étude de votre dossier, il manque : " + " ; ".join(pieces_client) + "."
            if pieces_client else
            "L'étude de votre dossier n'a pas pu être finalisée automatiquement. "
            "Votre conseiller reviendra vers vous.")

    # ── Cohérence LLM / règles ────────────────────────────────────────────────
    def _verifier_coherence(self, result_dict: dict) -> None:
        """
        Si un critère BLOQUANT est KO, une décision ELIGIBLE du LLM est
        ramenée à CONDITIONNEL (score plafonné à 59) et un risque est ajouté.
        """
        ko_bloquants = [
            c for c in result_dict.get("regulatoryChecks", [])
            if c["status"] == "KO" and c["blocking"]
        ]
        if not ko_bloquants:
            return

        criteres = ", ".join(c["criterion"] for c in ko_bloquants)
        if result_dict.get("eligibility") == "ELIGIBLE":
            logger.warning("Décision LLM ELIGIBLE corrigée en CONDITIONNEL (critères KO : %s)", criteres)
            result_dict["eligibility"] = "CONDITIONNEL"
            score = result_dict.get("eligibilityScore") or 0
            result_dict["eligibilityScore"] = min(int(score), 59)

        risks = result_dict.get("risks") or []
        risks.insert(0, {
            "level":       "HIGH",
            "description": f"Critère(s) réglementaire(s) non respecté(s) : {criteres}.",
            "source":      "Contrôle automatique BCT",
        })
        result_dict["risks"] = risks

    # ── Appel LLM ─────────────────────────────────────────────────────────────
    def _appeler_llm(self, document_text: str):
        return chat_completion(
            task="analyse",
            messages=[
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user",   "content": f"Voici le dossier à analyser :\n\n{document_text}"}
            ],
            temperature=0.1,
            max_tokens=3500,
            json_mode=True
        )

    # ── Parser résultat ───────────────────────────────────────────────────────
    def _parser_resultat(self, raw: str) -> dict:
        raw      = raw or ""
        json_str = raw

        if "```json" in raw:
            json_str = raw.split("```json")[1].split("```")[0].strip()
        elif "```" in raw:
            json_str = raw.split("```")[1].split("```")[0].strip()

        debut = json_str.find("{")
        fin   = json_str.rfind("}")
        if debut >= 0 and fin > debut:
            json_str = json_str[debut:fin+1]

        try:
            result = json.loads(json_str)
            result["rawExplanation"] = self._nettoyer_explanation(
                result.get("rawExplanation", "")
            )
            return result
        except json.JSONDecodeError:
            # Tentative de réparation si le JSON est tronqué (max_tokens atteint)
            reparation = self._tenter_reparation_json(json_str)
            if reparation is not None:
                logger.warning("JSON tronqué réparé automatiquement")
                reparation["rawExplanation"] = self._nettoyer_explanation(
                    reparation.get("rawExplanation", "")
                )
                return reparation

            logger.warning("JSON invalide et non réparable — retour fallback")
            return {
                "eligibility":    "INDETERMINE",
                "rawExplanation": "L'analyse n'a pas pu être complétée entièrement. Veuillez réessayer."
            }

    # ── Réparation JSON tronqué ────────────────────────────────────────────────
    def _tenter_reparation_json(self, json_str: str) -> Optional[dict]:
        """Ferme un JSON tronqué (accolades/crochets/guillemets manquants) et le reparse."""
        ouvertures_accolades = json_str.count("{")
        fermetures_accolades = json_str.count("}")
        ouvertures_crochets  = json_str.count("[")
        fermetures_crochets  = json_str.count("]")

        reparation = json_str
        if reparation.count('"') % 2 != 0:
            reparation += '"'

        reparation += "]" * max(0, ouvertures_crochets - fermetures_crochets)
        reparation += "}" * max(0, ouvertures_accolades - fermetures_accolades)

        try:
            return json.loads(reparation)
        except Exception:
            return None

    # ── Nettoyage rawExplanation ──────────────────────────────────────────────
    def _nettoyer_explanation(self, texte) -> str:
        """Si le modèle a mis du JSON dans rawExplanation par erreur, on l'extrait."""
        if not isinstance(texte, str):
            return str(texte) if texte else ""

        texte_propre = texte.strip()

        # Le modèle a répété tout le JSON dans ce champ
        if texte_propre.startswith("```json") or texte_propre.startswith("{"):
            try:
                interieur = texte_propre
                if "```json" in interieur:
                    interieur = interieur.split("```json")[1].split("```")[0].strip()
                debut = interieur.find("{")
                fin   = interieur.rfind("}")
                if debut >= 0 and fin > debut:
                    interieur = interieur[debut:fin+1]
                sous_json = json.loads(interieur)
                # On récupère la vraie explication imbriquée si elle existe
                if isinstance(sous_json, dict) and "rawExplanation" in sous_json:
                    return self._nettoyer_explanation(sous_json["rawExplanation"])
                return "Analyse effectuée — voir les métriques et le plan recommandé ci-dessus."
            except Exception:
                return "Analyse effectuée — voir les métriques et le plan recommandé ci-dessus."

        return texte_propre

    # ── Résultat d'échec ──────────────────────────────────────────────────────
    def _resultat_echec(self, explication: str, t0: float) -> dict:
        return {
            "eligibility":      "INDETERMINE",
            "eligibilityScore": 0,
            "summary":          explication,
            "financialMetrics": {},
            "strengths":        [],
            "weaknesses":       [],
            "risks":            [],
            "recommendedPlan":  [],
            "conditions":       [],
            "regulatoryChecks": [],
            "capacity":         {},
            "simulations":      [],
            "documentSources":  [],
            "donneesManquantes": [],
            "avertissements":   [],
            "scoreProvisoire":  False,
            "rawExplanation":   explication,
            "creditType":       "CONSOMMATION",
            "statut":           "FAILURE",
            "provider":         None,
            "versionRegles":    RULES_VERSION,
            "duree_ms":         round((time.time()-t0)*1000, 1),
            "depuis_cache":     False
        }

    # ── Stats ─────────────────────────────────────────────────────────────────
    def stats(self) -> dict:
        return {
            "cache_size": len(self._cache),
            "cache_max":  CACHE_MAX_SIZE,
            "llm":        llm_etat()
        }