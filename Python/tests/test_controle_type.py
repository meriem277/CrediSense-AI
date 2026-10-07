"""
Tests de services/controle_type.py (type déclaré par le client vs type détecté).

Lancer depuis le dossier Python/ :
    python -m unittest tests.test_controle_type -v
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from services.controle_type import evaluer_type  # noqa: E402


def classif(type_document, confiance, methode="embeddings"):
    return {"type_document": type_document, "confiance": confiance, "methode": methode}


class ControleTypeTest(unittest.TestCase):

    def test_meme_type_fiable_est_concordant(self):
        r = evaluer_type("FICHE_PAIE", classif("FICHE_PAIE", 0.71))
        self.assertTrue(r["fiable"])
        self.assertTrue(r["concordant"])
        self.assertEqual(r["typeRetenu"], "FICHE_PAIE")

    def test_type_different_et_fiable_est_un_conflit(self):
        r = evaluer_type("FICHE_PAIE", classif("RELEVE_BANCAIRE", 0.68))
        self.assertTrue(r["fiable"])
        self.assertFalse(r["concordant"])
        self.assertEqual(r["typeDetecte"], "RELEVE_BANCAIRE")
        # le contenu est un relevé : c'est ce type qui sert à l'extraction
        self.assertEqual(r["typeRetenu"], "RELEVE_BANCAIRE")

    def test_verdict_peu_fiable_n_est_jamais_un_conflit(self):
        # similarité sous le seuil des embeddings
        r = evaluer_type("FICHE_PAIE", classif("RELEVE_BANCAIRE", 0.40))
        self.assertFalse(r["fiable"])
        self.assertIsNone(r["concordant"])
        self.assertEqual(r["typeRetenu"], "FICHE_PAIE")   # on garde ce que le client a déclaré

    def test_zone_grise_sans_reponse_du_llm_n_est_pas_un_conflit(self):
        r = evaluer_type("CIN", classif("FICHE_PAIE", 0.45, "embeddings_llm_indisponible"))
        self.assertFalse(r["fiable"])
        self.assertIsNone(r["concordant"])

    def test_confiance_tres_basse_n_est_pas_un_conflit(self):
        r = evaluer_type("CIN", classif("AUTRE", 0.1, "embeddings_faible_confiance"))
        self.assertIsNone(r["concordant"])
        self.assertEqual(r["typeRetenu"], "CIN")

    def test_llm_exige_une_confiance_nette(self):
        incertain = evaluer_type("CIN", classif("FICHE_PAIE", 0.55, "llm_fallback"))
        self.assertIsNone(incertain["concordant"])
        net = evaluer_type("CIN", classif("FICHE_PAIE", 0.9, "llm_fallback"))
        self.assertFalse(net["concordant"])

    def test_detecte_autre_n_est_pas_un_conflit(self):
        r = evaluer_type("RELEVE_BANCAIRE", classif("AUTRE", 0.9, "llm_fallback"))
        self.assertFalse(r["fiable"])
        self.assertIsNone(r["concordant"])

    def test_type_non_declare_pas_de_comparaison_mais_le_type_detecte_sert(self):
        for declare in (None, "", "AUTRE", "autre"):
            r = evaluer_type(declare, classif("ATTESTATION_EMPLOI", 0.7))
            self.assertIsNone(r["concordant"], declare)
            self.assertEqual(r["typeRetenu"], "ATTESTATION_EMPLOI")

    def test_casse_ignoree(self):
        r = evaluer_type("fiche_paie", classif("FICHE_PAIE", 0.7))
        self.assertTrue(r["concordant"])

    def test_entrees_mal_formees_ne_plantent_pas(self):
        r = evaluer_type("CIN", {"confiance": "abc"})
        self.assertEqual(r["typeDetecte"], "AUTRE")
        self.assertIsNone(r["concordant"])
        self.assertEqual(r["typeRetenu"], "CIN")


if __name__ == "__main__":
    unittest.main()
