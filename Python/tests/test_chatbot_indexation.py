"""
Tests de l'indexation du chatbot (ChatbotService.indexer_documents).

Utilise le vrai faiss, mais un faux modèle d'embeddings (pas de téléchargement)
et un faux LLM qui compte ses appels.

Lancer depuis le dossier Python/ :
    python -m unittest tests.test_chatbot_indexation -v
"""
import sys
import types
import unittest
import zlib
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

# ── Faux routeur LLM, installé AVANT l'import du chatbot ─────────────────────
APPELS_LLM: list = []


class _ReponseLLM:
    content = "Réponse de test."
    provider = "faux"
    model = "faux"


def _chat_completion(task, messages, **kwargs):
    APPELS_LLM.append(task)
    return _ReponseLLM()


_faux_llm = types.ModuleType("services.llm_client")
_faux_llm.chat_completion = _chat_completion
_faux_llm.LLMUnavailableError = type("LLMUnavailableError", (RuntimeError,), {})
sys.modules["services.llm_client"] = _faux_llm

import services.chatbot_service as chatbot  # noqa: E402


class _FauxModeleEmbeddings:
    """Embeddings déterministes par sac de mots (assez pour tester la mécanique)."""
    DIM = 64

    def encode(self, textes, **kwargs):
        if isinstance(textes, str):
            textes = [textes]
        vecteurs = np.zeros((len(textes), self.DIM), dtype="float32")
        for i, texte in enumerate(textes):
            for mot in texte.lower().split():
                vecteurs[i, zlib.crc32(mot.encode()) % self.DIM] += 1.0
            norme = np.linalg.norm(vecteurs[i]) or 1.0
            vecteurs[i] /= norme
        return vecteurs


FICHE_PAIE = (
    "Document type FICHE_PAIE nom paie.pdf :\n"
    "BULLETIN DE PAIE\nSalaire de base 2 000,000 DT\nCotisations CNSS 180,000 DT\n"
    "NET A PAYER 1 820,000 DT\n"
)
RELEVE = (
    "Document type RELEVE_BANCAIRE nom releve.pdf :\n"
    "RELEVE DE COMPTE\nAncien solde 350,000 DT\nDebit remboursement credit 400,000 DT\n"
    "Nouveau solde 1 770,000 DT\n"
)


class TestIndexationChatbot(unittest.TestCase):

    def setUp(self):
        APPELS_LLM.clear()
        self._modele_reel = chatbot.SentenceTransformer
        chatbot.SentenceTransformer = lambda *a, **k: _FauxModeleEmbeddings()
        self.service = chatbot.ChatbotService()

    def tearDown(self):
        chatbot.SentenceTransformer = self._modele_reel

    def test_indexation_n_appelle_pas_le_llm(self):
        resultat = self.service.indexer_documents("d1", [FICHE_PAIE, RELEVE])

        self.assertEqual(APPELS_LLM, [], "l'indexation ne doit consommer aucun appel LLM")
        self.assertIn("d1", self.service._indexes)
        self.assertGreater(resultat["nb_chunks"], 0)

    def test_chaque_document_est_etiquete_separement(self):
        self.service.indexer_documents("d1", [FICHE_PAIE, RELEVE])

        documents = {c["document"] for c in self.service._indexes["d1"]["chunks"]}
        self.assertIn("Fiche de paie (doc 1)", documents)
        self.assertIn("Relevé bancaire (doc 2)", documents)

    def test_question_apres_indexation_reutilise_l_index_et_couvre_les_deux_documents(self):
        self.service.indexer_documents("d1", [FICHE_PAIE, RELEVE])
        index_avant = self.service._indexes["d1"]["index"]

        reponse = self.service.poser_question("Quel est le salaire net ?", "d1", "12345678")

        self.assertEqual(reponse["statut"], "SUCCESS")
        self.assertEqual(APPELS_LLM, ["chat"], "un seul appel LLM : celui de la question")
        self.assertIs(self.service._indexes["d1"]["index"], index_avant, "l'index ne doit pas être reconstruit")
        documents_cites = {s["document"] for s in reponse["sources"]}
        self.assertIn("Fiche de paie (doc 1)", documents_cites)
        self.assertIn("Relevé bancaire (doc 2)", documents_cites)

    def test_reindexation_remplace_l_ancien_index(self):
        self.service.indexer_documents("d1", [FICHE_PAIE])
        self.service.indexer_documents("d1", [RELEVE])

        documents = {c["document"] for c in self.service._indexes["d1"]["chunks"]}
        self.assertEqual(documents, {"Relevé bancaire (doc 1)"})

    def test_textes_vides_ne_creent_pas_d_index(self):
        resultat = self.service.indexer_documents("d2", ["", "   "])

        self.assertEqual(resultat["nb_chunks"], 0)
        self.assertNotIn("d2", self.service._indexes)
        self.assertEqual(APPELS_LLM, [])


if __name__ == "__main__":
    unittest.main()
