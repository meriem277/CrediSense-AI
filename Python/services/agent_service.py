# services/agent_service.py
"""
Agent IA Crédit Consommation — CrediSense
Migration de ConsommationAgentService.java vers Python
- Retry automatique avec backoff
- Validation Pydantic du résultat
- Cache par hash du dossier
- Logging structuré avec métriques
"""

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

# ── Schema Pydantic — résultat analyse ───────────────────────────────────────

class MetriquesFinancieres(BaseModel):
    dti:            Optional[float] = None  # ratio dette/revenu %
    monthlyIncome:  Optional[float] = None  # revenu mensuel net
    requestedAmount: Optional[float] = None # montant demandé
    duration:       Optional[int]   = None  # durée en mois
    monthlyPayment: Optional[float] = None  # mensualité estimée
    existingDebts:  Optional[float] = None  # total dettes existantes

    class Config:
        extra = "ignore"

class CreditAnalysisResult(BaseModel):
    eligibility:      str                          = "INDETERMINE"
    eligibilityScore: Optional[int]                = None
    financialMetrics: Optional[MetriquesFinancieres] = None
    risks:            list[str]                    = []
    recommendedPlan:  list[str]                    = []
    rawExplanation:   Optional[str]                = None
    creditType:       str                          = "CONSOMMATION"

    class Config:
        extra = "ignore"

# ── Prompt système ─────────────────────────────────────────────────────────────
SYSTEM_PROMPT = """Tu es un expert senior en crédit à la consommation bancaire tunisien.
Analyse le dossier fourni et retourne UNIQUEMENT un JSON valide avec cette structure exacte :
{
  "eligibility": "ELIGIBLE|REFUS|CONDITIONNEL",
  "eligibilityScore": <0-100>,
  "financialMetrics": {
    "dti": <ratio dette/revenu en %>,
    "monthlyIncome": <revenu mensuel net en DT>,
    "requestedAmount": <montant demandé en DT>,
    "duration": <durée en mois>,
    "monthlyPayment": <mensualité estimée en DT>,
    "existingDebts": <total dettes existantes en DT>
  },
  "risks": ["risque1", "risque2"],
  "recommendedPlan": ["étape1", "étape2"],
  "rawExplanation": "<explication détaillée en français>"
}

Critères crédit consommation BCT Tunisie :
- DTI : acceptable si < 30%, risque si 30-35%, REFUS si > 35%
- Montant max : 5× le salaire mensuel net
- Durée max : 84 mois (7 ans)
- Ancienneté emploi minimum : 6 mois
- Âge fin crédit ≤ 70 ans
- Historique : BON = favorable, MAUVAIS = refus probable
- Incidents paiement > 0 = risque élevé"""


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

    def analyser_consommation(self, document_text: str) -> dict:
        """
        Analyse un dossier de crédit consommation.
        Migration de ConsommationAgentService.analyse() Java.

        Args:
            document_text: texte combiné JSON financier + OCR du dossier

        Returns:
            {
              "eligibility":      str,
              "eligibilityScore": int,
              "financialMetrics": dict,
              "risks":            list,
              "recommendedPlan":  list,
              "rawExplanation":   str,
              "creditType":       "CONSOMMATION",
              "statut":           "SUCCESS" | "FAILURE",
              "duree_ms":         float,
              "depuis_cache":     bool
            }
        """
        t0 = time.time()
        logger.info("Analyse consommation — %d chars", len(document_text))

        # 1. Cache
        cache_key = hashlib.md5(document_text.encode()).hexdigest()
        if cache_key in self._cache:
            logger.info("Cache HIT agent consommation")
            result = self._cache[cache_key].copy()
            result["depuis_cache"] = True
            result["duree_ms"]     = round((time.time()-t0)*1000, 1)
            return result

        try:
            # 2. Appeler GROQ avec retry
            raw = self._appeler_groq(document_text)

            # 3. Parser le résultat
            result_dict = self._parser_resultat(raw)

            # 4. Valider avec Pydantic
            try:
                validated = CreditAnalysisResult(**result_dict)
                result_dict = validated.model_dump()
            except Exception as e:
                logger.warning("Validation partielle : %s", str(e))

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

            # 5. Mettre en cache
            if len(self._cache) >= CACHE_MAX_SIZE:
                oldest = next(iter(self._cache))
                del self._cache[oldest]
            self._cache[cache_key] = result_dict

            return result_dict

        except Exception as e:
            logger.error("Erreur analyse consommation : %s", str(e), exc_info=True)
            return {
                "eligibility":      "INDETERMINE",
                "eligibilityScore": None,
                "financialMetrics": None,
                "risks":            [],
                "recommendedPlan":  [],
                "rawExplanation":   f"Erreur analyse : {str(e)}",
                "creditType":       "CONSOMMATION",
                "statut":           "FAILURE",
                "duree_ms":         round((time.time()-t0)*1000, 1),
                "depuis_cache":     False
            }

    # ── Appel GROQ avec retry ─────────────────────────────────────────────────

    @retry(
        stop=stop_after_attempt(3),
        wait=wait_exponential(multiplier=1, min=2, max=10),
        retry=retry_if_exception_type((httpx.TimeoutException, httpx.HTTPStatusError)),
        before_sleep=before_sleep_log(logger, logging.WARNING)
    )
    def _appeler_groq(self, document_text: str) -> str:
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

        if response.status_code == 429:
            response.raise_for_status()
        if response.status_code >= 500:
            response.raise_for_status()

        response.raise_for_status()
        return response.json()["choices"][0]["message"]["content"]

    # ── Parser résultat ───────────────────────────────────────────────────────

    def _parser_resultat(self, raw: str) -> dict:
        """Parse le JSON retourné par GROQ — même logique que Java."""
        json_str = raw

        if "```json" in raw:
            json_str = raw.split("```json")[1].split("```")[0].strip()
        elif "```" in raw:
            json_str = raw.split("```")[1].split("```")[0].strip()

        # Extraire le premier objet JSON
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

    def stats(self) -> dict:
        return {
            "cache_size": len(self._cache),
            "cache_max":  CACHE_MAX_SIZE,
            "model":      GROQ_MODEL
        }

    def __del__(self):
        if hasattr(self, "_http_client"):
            self._http_client.close()