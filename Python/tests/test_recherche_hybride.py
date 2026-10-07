"""
Tests de la recherche hybride du chatbot (services/recherche_hybride.py) et de son
intégration dans le retriever (ChatbotService._retriever).

Embeddings et LLM simulés : aucun téléchargement, aucun appel réseau.

Lancer depuis le dossier Python/ :
    python -m unittest tests.test_recherche_hybride -v
"""
import sys
import types
import unittest
import zlib
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

# Faux routeur LLM, installé seulement s'il n'est pas déjà importé (un autre test peut l'avoir fait)
_stub = types.ModuleType("services.llm_client")
_stub.chat_completion = lambda *a, **k: None
_stub.LLMUnavailableError = type("LLMUnavailableError", (RuntimeError,), {})
_stub.etat = lambda: {}
sys.modules.setdefault("services.llm_client", _stub)

import services.chatbot_service as chatbot  # noqa: E402
from services.recherche_hybride import Bm25, fusion_rrf, tokeniser  # noqa: E402


class FauxEmbeddings:
    """Embeddings déterministes par sac de mots."""
    def encode(self, textes, **kwargs):
        if isinstance(textes, str):
            textes = [textes]
        vecteurs = np.zeros((len(textes), 64), dtype="float32")
        for i, texte in enumerate(textes):
            for mot in texte.lower().split():
                vecteurs[i, zlib.crc32(mot.encode()) % 64] += 1.0
            vecteurs[i] /= np.linalg.norm(vecteurs[i]) or 1.0
        return vecteurs


class TokeniserTest(unittest.TestCase):

    def test_un_montant_donne_sa_valeur_complete_et_sa_partie_entiere(self):
        jetons = tokeniser("NET A PAYER 2 100,000 DT")
        self.assertIn("2100", jetons)
        self.assertIn("2100000", jetons)

    def test_accents_et_mots_vides(self):
        jetons = tokeniser("Quel est le salaire de l'employé ?")
        self.assertIn("salaire", jetons)
        self.assertIn("employe", jetons)          # sans accent
        for vide in ("quel", "est", "le", "de"):
            self.assertNotIn(vide, jetons)

    def test_numero_de_cin_conserve(self):
        self.assertIn("12015060", tokeniser("CIN : 12015060"))

    def test_texte_vide(self):
        self.assertEqual(tokeniser(""), [])
        self.assertEqual(tokeniser(None), [])


class Bm25Test(unittest.TestCase):

    DOCS = [
        tokeniser("VIREMENT SALAIRE EXEMPLE TECH SARL 2 100,000 3 250,000"),
        tokeniser("RETRAIT DAB 200,000 2 800,000"),
        tokeniser("PRELEVEMENT ECHEANCE PRET CONSOMMATION 250,000 3 000,000"),
    ]

    def test_un_montant_exact_retrouve_le_bon_document(self):
        scores = Bm25(self.DOCS).scores(tokeniser("combien pour 250,000 ?"))
        self.assertEqual(int(np.argmax(scores)), 2)

    def test_la_partie_entiere_suffit(self):
        scores = Bm25(self.DOCS).scores(tokeniser("2100"))
        self.assertEqual(int(np.argmax(scores)), 0)

    def test_aucun_mot_commun_donne_zero(self):
        scores = Bm25(self.DOCS).scores(tokeniser("hypothèque immobilière"))
        self.assertEqual(float(scores.sum()), 0.0)

    def test_collection_vide(self):
        self.assertEqual(len(Bm25([]).scores(["x"])), 0)


class FusionTest(unittest.TestCase):

    def test_un_element_bien_classe_par_les_deux_gagne(self):
        fusion = fusion_rrf([[3, 1, 2], [1, 3, 2]])
        meilleur = max(fusion, key=fusion.get)
        self.assertIn(meilleur, (1, 3))
        self.assertGreater(fusion[meilleur], fusion[2])

    def test_un_element_present_dans_un_seul_classement_reste_dans_le_resultat(self):
        fusion = fusion_rrf([[0, 1], [5]])
        self.assertIn(5, fusion)

    def test_classement_vide(self):
        self.assertEqual(fusion_rrf([[], []]), {})


RELEVE = (
    "Document type RELEVE_BANCAIRE nom releve.pdf :\n"
    "RELEVÉ DE COMPTE\nDate\nLibellé\nDébit\nCrédit\nSolde\n"
    "01/07/2026\nANCIEN SOLDE\n1 150,000\n"
    "03/07/2026\nVIREMENT SALAIRE EXEMPLE TECH SARL\n2 100,000\n3 250,000\n"
    "05/07/2026\nRETRAIT DAB\n200,000\n3 050,000\n"
    "09/07/2026\nVIREMENT VERS COMPTE EPARGNE\n777,777\n2 272,223\n"
    "NOUVEAU SOLDE\n2 272,223\n"
)
FICHE_PAIE = (
    "Document type FICHE_PAIE nom paie.pdf :\n"
    "BULLETIN DE PAIE\nSalaire de base 2 400,000\nNET A PAYER 2 100,000 DT\n"
)


class RetrieverHybrideTest(unittest.TestCase):

    def setUp(self):
        self._reel = chatbot.SentenceTransformer
        chatbot.SentenceTransformer = lambda *a, **k: FauxEmbeddings()
        self.service = chatbot.ChatbotService()
        self.service.indexer_documents("d1", [FICHE_PAIE, RELEVE])

    def tearDown(self):
        chatbot.SentenceTransformer = self._reel

    def test_l_index_contient_un_index_lexical(self):
        self.assertIn("bm25", self.service._indexes["d1"])

    def test_le_releve_est_decoupe_par_operation(self):
        chunks = [c for c in self.service._indexes["d1"]["chunks"] if c["document"].startswith("Relevé")]
        self.assertTrue(any("777,777" in c["text"] and "RETRAIT" not in c["text"] for c in chunks))

    def test_un_montant_exact_remonte_en_premier(self):
        resultats = self.service._retriever("Que signifie 777,777 ?", "d1")
        self.assertIn("777,777", resultats[0]["text"])
        self.assertGreater(resultats[0]["score_lexical"], 0)

    def test_chaque_resultat_porte_les_trois_scores(self):
        for r in self.service._retriever("salaire", "d1"):
            self.assertIn("score", r)            # similarité sémantique
            self.assertIn("score_lexical", r)    # BM25
            self.assertIn("score_fusion", r)     # fusion RRF

    def test_resultats_tries_par_score_de_fusion(self):
        scores = [r["score_fusion"] for r in self.service._retriever("salaire net", "d1")]
        self.assertEqual(scores, sorted(scores, reverse=True))

    def test_la_couverture_de_chaque_document_est_conservee(self):
        documents = {r["document"] for r in self.service._retriever("Quel est le salaire ?", "d1")}
        self.assertEqual(len(documents), 2)

    def test_index_sans_bm25_retombe_sur_la_recherche_semantique(self):
        del self.service._indexes["d1"]["bm25"]
        resultats = self.service._retriever("salaire", "d1")
        self.assertTrue(resultats)
        self.assertTrue(all(r["score_lexical"] == 0.0 for r in resultats))


if __name__ == "__main__":
    unittest.main()
