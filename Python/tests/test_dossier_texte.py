"""
Tests de services/dossier_texte.py (aucune dépendance externe).

Lancer depuis le dossier Python/ :
    python -m unittest tests.test_dossier_texte -v
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from services.dossier_texte import preparer_texte_dossier, MARQUEUR_OMISSION  # noqa: E402

MAX = 12_000


def doc(titre: str, taille: int, fin: str = "") -> str:
    """Document factice : en-tête + texte de remplissage + éventuelle fin identifiable."""
    corps = ("ligne de texte OCR " * 5 + "\n") * (taille // 100)
    return f"=== {titre} ===\n{corps}{fin}\n\n"


class TestPreparerTexteDossier(unittest.TestCase):

    def test_texte_court_inchange(self):
        texte = doc("CIN", 800)
        sortie, avert = preparer_texte_dossier(texte, MAX)
        self.assertEqual(sortie, texte)
        self.assertEqual(avert, [])

    def test_limite_nulle_ou_negative_desactive_la_coupe(self):
        texte = doc("RELEVE", 50_000)
        sortie, avert = preparer_texte_dossier(texte, 0)
        self.assertEqual(sortie, texte)
        self.assertEqual(avert, [])

    def test_cinq_longs_documents_aucun_supprime(self):
        """Cas du bug d'origine : avec une coupe à 4000 caractères, seuls les 1-2 premiers survivaient."""
        titres = ["CIN", "FICHE_PAIE", "RELEVE_BANCAIRE", "ATTESTATION_EMPLOI", "JUSTIFICATIF_DOMICILE"]
        texte = "=== DEMANDE DE CREDIT (formulaire client) ===\nMontant demande: 20000 TND\n\n"
        texte += "".join(doc(t, 5000) for t in titres)

        sortie, avert = preparer_texte_dossier(texte, MAX)

        self.assertLessEqual(len(sortie), MAX)
        for t in titres:
            self.assertIn(f"=== {t} ===", sortie, f"le document {t} a disparu")
        # La demande de crédit (courte) est gardée en entier et sans avertissement
        self.assertIn("Montant demande: 20000 TND", sortie)
        self.assertEqual(len(avert), 5)
        self.assertFalse(any("DEMANDE DE CREDIT" in a for a in avert))

    def test_un_seul_document_long_les_courts_restent_entiers(self):
        texte = doc("CIN", 800) + doc("FICHE_PAIE", 1500) + doc("RELEVE_BANCAIRE", 30_000)
        sortie, avert = preparer_texte_dossier(texte, MAX)

        self.assertLessEqual(len(sortie), MAX)
        self.assertIn(doc("CIN", 800), sortie)
        self.assertIn(doc("FICHE_PAIE", 1500), sortie)
        self.assertEqual(len(avert), 1)
        self.assertIn("RELEVE_BANCAIRE", avert[0])

    def test_debut_et_fin_d_un_document_raccourci_sont_gardes(self):
        texte = doc("RELEVE_BANCAIRE", 40_000, fin="NOUVEAU SOLDE : 1 234,500 DT")
        sortie, _ = preparer_texte_dossier(texte, MAX)

        self.assertIn("=== RELEVE_BANCAIRE ===", sortie)          # début
        self.assertIn("NOUVEAU SOLDE : 1 234,500 DT", sortie)      # fin
        self.assertIn(MARQUEUR_OMISSION.strip(), sortie)

    def test_texte_sans_en_tetes(self):
        texte = "x" * 30_000
        sortie, avert = preparer_texte_dossier(texte, MAX)

        self.assertLessEqual(len(sortie), MAX)
        self.assertEqual(len(avert), 1)
        self.assertIn("raccourci", avert[0])

    def test_texte_avant_le_premier_en_tete_est_conserve(self):
        texte = "Introduction libre\n\n" + doc("RELEVE", 30_000)
        sortie, _ = preparer_texte_dossier(texte, MAX)
        self.assertTrue(sortie.startswith("Introduction libre"))

    def test_budget_tres_serre_ne_plante_pas(self):
        texte = "".join(doc(f"DOC{i}", 5000) for i in range(10))
        sortie, avert = preparer_texte_dossier(texte, 1500)
        for i in range(10):
            self.assertIn(f"=== DOC{i} ===", sortie)
        self.assertTrue(avert)

    def test_texte_vide_ou_none(self):
        self.assertEqual(preparer_texte_dossier("", MAX), ("", []))
        self.assertEqual(preparer_texte_dossier(None, MAX), ("", []))


if __name__ == "__main__":
    unittest.main()
