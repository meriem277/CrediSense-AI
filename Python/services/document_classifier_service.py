# services/document_classifier_service.py
"""
Orchestrateur de classification à 2 niveaux (cascade) :

  1. Embeddings (nlp_service.py) — rapide, gratuit, local.
     Si le score de confiance est élevé → on garde ce résultat, FIN.

  2. LLM (llm_classifier_service.py) — plus lent, appel API GROQ.
     Utilisé UNIQUEMENT quand les embeddings sont dans une "zone grise"
     (ni assez confiants pour trancher, ni assez bas pour dire AUTRE
     directement).

But : la majorité des documents propres sont classés en quelques ms sans
jamais appeler le LLM ; seuls les cas ambigus consomment un appel GROQ.
"""

import logging

from services.nlp_service import NLPClassifier
from services.llm_classifier_service import LLMClassifierService

logger = logging.getLogger(__name__)

# Au-dessus de ce seuil : on fait confiance aux embeddings directement.
SEUIL_CONFIANCE_ELEVEE = 0.55

# En dessous de ce seuil : probablement "AUTRE" de toute façon,
# on épargne l'appel LLM (peu de chances qu'il change le verdict).
SEUIL_CONFIANCE_BASSE = 0.20


class DocumentClassifierService:

    def __init__(self):
        # Chargés une seule fois au démarrage du service (comme le fait
        # déjà OcrService avec Doctr) — évite de recharger les modèles
        # à chaque appel.
        self.embeddings_classifier = NLPClassifier()
        self.llm_classifier = LLMClassifierService()

    def classify(self, texte_ocr: str, dossier_id: str | None = None) -> dict:
        """
        Retourne le résultat de classification final, avec le champ
        "methode" indiquant comment la décision a été prise :
        - "embeddings"                 → confiance élevée, LLM non appelé
        - "embeddings_faible_confiance" → confiance très basse, LLM non appelé
        - "llm_fallback"               → zone grise, LLM a tranché
        """
        resultat_embeddings = self.embeddings_classifier.classify(texte_ocr, dossier_id)
        confiance = resultat_embeddings["confiance"]

        if confiance >= SEUIL_CONFIANCE_ELEVEE:
            logger.info(
                "Classification tranchée par embeddings (confiance=%.2f, dossier=%s)",
                confiance, dossier_id
            )
            return {
                **resultat_embeddings,
                "methode": "embeddings",
            }

        if confiance < SEUIL_CONFIANCE_BASSE:
            logger.info(
                "Confiance trop basse (%.2f) — pas d'appel LLM, dossier=%s",
                confiance, dossier_id
            )
            return {
                **resultat_embeddings,
                "methode": "embeddings_faible_confiance",
            }

        logger.info(
            "Zone grise (confiance=%.2f) — appel LLM pour validation, dossier=%s",
            confiance, dossier_id
        )
        resultat_llm = self.llm_classifier.classify(texte_ocr, dossier_id)
        return {
            **resultat_llm,
            "methode": "llm_fallback",
            "confiance_embeddings_initiale": confiance,
        }