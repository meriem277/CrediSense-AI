# services/document_classifier_service.py
"""
Orchestrateur de classification à 3 niveaux (cascade) :

  0. Règles par mots-clés (classification_regles.py) — instantané, gratuit, reproductible.
     Répond seulement quand le verdict est net (titre du document, vocabulaire propre) ;
     sinon elle s'abstient et la cascade continue.

  1. Embeddings (nlp_service.py) — rapide, gratuit, local.
     Si le score de confiance est élevé → on garde ce résultat, FIN.

  2. LLM (llm_classifier_service.py) — appel via le routeur LLM commun.
     Utilisé UNIQUEMENT quand les embeddings sont dans une "zone grise"
     (ni assez confiants pour trancher, ni assez bas pour dire AUTRE
     directement).

Si le LLM échoue (quota, réponse invalide), on GARDE le verdict des
embeddings au lieu de forcer "AUTRE" : mieux vaut une classification
incertaine qu'un document rejeté à cause d'une erreur réseau.

But : la majorité des documents propres sont classés en quelques ms sans
jamais appeler le LLM ; seuls les cas ambigus consomment un appel.
"""

import logging

from services.classification_regles import classer_par_regles
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
        # Chargés une seule fois au démarrage du service — évite de
        # recharger les modèles à chaque appel.
        self.embeddings_classifier = NLPClassifier()
        self.llm_classifier = LLMClassifierService()

    def classify(self, texte_ocr: str, dossier_id: str | None = None) -> dict:
        """
        Retourne le résultat de classification final, avec le champ
        "methode" indiquant comment la décision a été prise :
        - "regles"                      → mots-clés nets (titre du document…), rien d'autre appelé
        - "embeddings"                  → confiance élevée, LLM non appelé
        - "embeddings_faible_confiance" → confiance très basse, LLM non appelé
        - "llm_fallback"                → zone grise, LLM a tranché
        - "embeddings_llm_indisponible" → zone grise, LLM en échec,
                                          verdict des embeddings conservé
        """
        verdict_regles = classer_par_regles(texte_ocr)
        if verdict_regles is not None:
            logger.info(
                "Classification tranchée par règles (%s, score=%s, mots=%s, dossier=%s)",
                verdict_regles["type_document"], verdict_regles["score_regles"],
                verdict_regles["mots_cles"], dossier_id
            )
            return verdict_regles

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

        if resultat_llm.get("echec"):
            logger.warning(
                "LLM indisponible pour la classification — verdict embeddings conservé "
                "(%s, confiance=%.2f, dossier=%s)",
                resultat_embeddings.get("type_document"), confiance, dossier_id
            )
            return {
                **resultat_embeddings,
                "methode": "embeddings_llm_indisponible",
                "raison_llm": resultat_llm.get("justification"),
            }

        return {
            **resultat_llm,
            "methode": "llm_fallback",
            "confiance_embeddings_initiale": confiance,
        }