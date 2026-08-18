# services/agent_service.py

import json
import logging
import time
import hashlib
from typing import Optional

import httpx
from pydantic import BaseModel, Field
from tenacity import (
    retry, stop_after_attempt,
    wait_exponential, retry_if_exception_type,
    before_sleep_log
)
from config import GROQ_API_KEY, GROQ_MODEL, GROQ_API_URL

logger = logging.getLogger(__name__)

CACHE_MAX_SIZE = 128

# ── Schéma Pydantic ───────────────────────────────────────────────────────────

class MetriquesFinancieres(BaseModel):
    dti:             Optional[float] = None
    monthlyIncome:   Optional[float] = None
    requestedAmount: Optional[float] = None
    duration:        Optional[int]   = None
    monthlyPayment:  Optional[float] = None
    existingDebts:   Optional[float] = None

    class Config:
        extra = "ignore"

class CreditAnalysisResult(BaseModel):
    eligibility:      str                            = "INDETERMINE"
    eligibilityScore: Optional[int]                  = None
    financialMetrics: Optional[MetriquesFinancieres] = None
    risks:            list                           = []
    recommendedPlan:  list                           = []
    rawExplanation:   Optional[str]                  = None
    creditType:       str                            = "CONSOMMATION"

    class Config:
        extra = "ignore"

# ── System Prompt ─────────────────────────────────────────────────────────────

SYSTEM_PROMPT = """Tu es un expert senior en analyse de crédit à la consommation pour Attijariwafa Bank Tunisie.

MISSION : Analyser le dossier bancaire fourni et retourner UNIQUEMENT un JSON valide.

CRITÈRES RÉGLEMENTAIRES BCT TUNISIE :
- DTI (taux d'endettement) : ACCEPTABLE < 30% | RISQUE 30-35% | REFUS > 35%
- Montant maximum accordé : 5 × salaire mensuel net
- Durée maximale : 84 mois (7 ans)
- Ancienneté emploi minimum : 6 mois
- Âge à la fin du crédit : ≤ 70 ans
- Incidents de paiement > 0 : risque élevé
- CDI : favorable | CDD : risque modéré | Indépendant : vérification bilan

LOGIQUE DE SCORING (0-100) :
- 80-100 : ELIGIBLE (dossier solide)
- 60-79  : ELIGIBLE (dossier acceptable)
- 40-59  : CONDITIONNEL (garanties supplémentaires requises)
- 0-39   : REFUS (critères non satisfaits)

FORMAT DE RÉPONSE — JSON STRICT UNIQUEMENT :
{
  "eligibility": "ELIGIBLE" | "CONDITIONNEL" | "REFUS",
  "eligibilityScore": <entier 0-100>,
  "financialMetrics": {
    "dti": <float en %>,
    "monthlyIncome": <float en DT>,
    "requestedAmount": <float en DT>,
    "duration": <entier en mois>,
    "monthlyPayment": <float en DT>,
    "existingDebts": <float en DT>
  },
  "risks": [
    {
      "level": "HIGH" | "MEDIUM" | "LOW",
      "description": "<description précise du risque>",
      "source": "<document source>"
    }
  ],
  "recommendedPlan": [
    {
      "priority": <entier 1-5>,
      "action": "<action concrète recommandée>",
      "rationale": "<justification>",
      "source": "<document source>"
    }
  ],
  "documentSources": [
    {
      "field": "<champ extrait>",
      "value": "<valeur avec unité>",
      "foundIn": "<document source>"
    }
  ],
  "rawExplanation": "<explication complète minimum 5 phrases>"
}

RÈGLES ABSOLUES :
- Retourner UNIQUEMENT le JSON — aucun texte avant ou après
- Tous les montants en DT (Dinar Tunisien)
- rawExplanation minimum 5 phrases détaillées en français professionnel
- documentSources doit lister TOUTES les données financières extraites
- Si une donnée est absente, utiliser 0 ou null
- risks = [] si aucun risque identifié
"""

# ── Classe principale ─────────────────────────────────────────────────────────

class AgentService:

    _cache: dict = {}

    def __init__(self):
        self._http_client = httpx.Client(
            timeout=httpx.Timeout(45.0, connect=5.0),
            headers={
                "Authorization": f"Bearer {GROQ_API_KEY}",
                "Content-Type":  "application/json"
            }
        )
        logger.info("AgentService consommation initialisé")

    # ── Point d'entrée principal ──────────────────────────────────────────────
    def analyser_consommation(self, document_text: str) -> dict:  # ✅ indenté dans la classe
        t0 = time.time()
        logger.info("Analyse consommation — %d chars", len(document_text))

        # Cache
        cache_key = hashlib.md5(document_text.encode()).hexdigest()
        if cache_key in self._cache:
            logger.info("Cache HIT agent consommation")
            result = self._cache[cache_key].copy()
            result["depuis_cache"] = True
            result["duree_ms"]     = round((time.time()-t0)*1000, 1)
            return result

        try:
            raw         = self._appeler_groq(document_text)
            result_dict = self._parser_resultat(raw)

            # Validation Pydantic
            try:
                validated   = CreditAnalysisResult(**result_dict)
                result_dict = validated.model_dump()
            except Exception as e:
                logger.warning("Validation partielle : %s", str(e))

            # Génère documentSources si absent
            if not result_dict.get('documentSources'):
                result_dict['documentSources'] = []
                metrics = result_dict.get('financialMetrics') or {}
                if isinstance(metrics, dict):
                    sources = [
                        ('monthlyIncome',   'Revenu mensuel net',       '{:,.2f} DT', 'Fiche de paie'),
                        ('dti',             "Taux d'endettement (DTI)", '{}%',        'Calcul automatique'),
                        ('requestedAmount', 'Montant demandé',          '{:,.0f} DT', 'Formulaire de demande'),
                        ('monthlyPayment',  'Mensualité estimée',       '{:,.2f} DT', 'Simulation crédit'),
                        ('duration',        'Durée',                    '{} mois',    'Formulaire de demande'),
                        ('existingDebts',   'Dettes existantes',        '{:,.0f} DT', 'Relevé bancaire'),
                    ]
                    for key, field, fmt, found_in in sources:
                        val = metrics.get(key)
                        if val is not None:
                            try:
                                result_dict['documentSources'].append({
                                    'field':   field,
                                    'value':   fmt.format(val),
                                    'foundIn': found_in
                                })
                            except Exception:
                                pass

            duree_ms = round((time.time()-t0)*1000, 1)
            logger.info(
                "Analyse OK — eligibility=%s, score=%s, %.0fms",
                result_dict.get("eligibility"),
                result_dict.get("eligibilityScore"),
                duree_ms
            )

            result_dict["statut"]       = "SUCCESS"
            result_dict["duree_ms"]     = duree_ms
            result_dict["depuis_cache"] = False

            # Mise en cache
            if len(self._cache) >= CACHE_MAX_SIZE:
                oldest = next(iter(self._cache))
                del self._cache[oldest]
            self._cache[cache_key] = result_dict

            return result_dict

        except Exception as e:
            logger.error("Erreur analyse consommation : %s", str(e), exc_info=True)
            return {
                "eligibility":      "INDETERMINE",
                "eligibilityScore": 0,
                "financialMetrics": {},
                "risks":            [],
                "recommendedPlan":  [],
                "documentSources":  [],
                "rawExplanation":   f"Erreur analyse : {str(e)}",
                "creditType":       "CONSOMMATION",
                "statut":           "FAILURE",
                "duree_ms":         round((time.time()-t0)*1000, 1),
                "depuis_cache":     False
            }

    # ── Appel GROQ avec retry ─────────────────────────────────────────────────
    @retry(
        stop=stop_after_attempt(5),
        wait=wait_exponential(multiplier=2, min=4, max=30),
        retry=retry_if_exception_type((httpx.TimeoutException, httpx.HTTPStatusError)),
        before_sleep=before_sleep_log(logger, logging.WARNING)
    )
    def _appeler_groq(self, document_text: str) -> str:  # ✅ indenté dans la classe
        payload = {
            "model":    GROQ_MODEL,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user",   "content": f"Voici le dossier à analyser :\n\n{document_text[:4000]}"}
            ],
            "temperature": 0.1,
            "max_tokens":  2000
        }

        response = self._http_client.post(GROQ_API_URL, json=payload)
        response.raise_for_status()
        return response.json()["choices"][0]["message"]["content"]

    # ── Parser résultat ───────────────────────────────────────────────────────
    def _parser_resultat(self, raw: str) -> dict:  # ✅ indenté dans la classe
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
            return json.loads(json_str)
        except json.JSONDecodeError:
            logger.warning("JSON invalide — retour fallback")
            return {
                "eligibility":    "INDETERMINE",
                "rawExplanation": raw
            }

    # ── Stats ─────────────────────────────────────────────────────────────────
    def stats(self) -> dict:  # ✅ indenté dans la classe
        return {
            "cache_size": len(self._cache),
            "cache_max":  CACHE_MAX_SIZE,
            "model":      GROQ_MODEL
        }

    def __del__(self):
        if hasattr(self, "_http_client"):
            self._http_client.close()