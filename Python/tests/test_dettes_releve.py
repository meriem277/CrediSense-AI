"""
Tests de services/dettes_releve.py (détection des échéances de crédit dans un relevé).

Lancer depuis le dossier Python/ :
    python -m unittest tests.test_dettes_releve -v
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from services.dettes_releve import detecter_dettes_releve  # noqa: E402

ENTETE = "=== RELEVE_BANCAIRE (RELEVE_BANCAIRE.pdf) ===\n"

# PDF natif : PyMuPDF donne UNE CELLULE PAR LIGNE (date / libellé / montants)
RELEVE_NATIF = """RELEVÉ DE COMPTE
Période : du 01/07/2026 au 30/09/2026
Date
Libellé
Débit
Crédit
Solde
01/09/2026
ANCIEN SOLDE
2 285,550
03/09/2026
VIREMENT SALAIRE EXEMPLE TECH SARL
2 100,000
4 385,550
05/09/2026
PRELEVEMENT ECHEANCE PRET CONSOMMATION N°
2024-0457
250,000
4 135,550
06/09/2026
RETRAIT DAB
200,000
3 935,550
Total des débits du trimestre (01/07 au 30/09)
4 596,350
NOUVEAU SOLDE au 30/09/2026
2 853,650 DT
"""

# Sortie de l'OCR : une opération par ligne
RELEVE_OCR = """RELEVÉ DE COMPTE Banque Exemple
Date Libellé Débit Crédit Solde
01/09/2026 ANCIEN SOLDE 2 285,550
03/09/2026 VIREMENT SALAIRE EXEMPLE TECH SARL 2 100,000 4 385,550
05/09/2026 PRELEVEMENT ECHEANCE PRET CONSOMMATION N° 250,000 4 135,550
2024-0457
06/09/2026 RETRAIT DAB 200,000 3 935,550
"""

RELEVE_SANS_CREDIT = """RELEVÉ DE COMPTE
Date Libellé Débit Crédit Solde
03/09/2026 VIREMENT SALAIRE EXEMPLE TECH SARL 2 100,000 4 385,550
06/09/2026 RETRAIT DAB 200,000 4 185,550
15/09/2026 VIREMENT PERMANENT LOYER 600,000 3 585,550
"""


class TestDetectionDettes(unittest.TestCase):

    def test_pdf_natif_une_cellule_par_ligne(self):
        r = detecter_dettes_releve(ENTETE + RELEVE_NATIF)
        self.assertTrue(r["releve_present"])
        self.assertEqual(r["mensualite"], 250.0)
        self.assertFalse(r["approximatif"])

    def test_sortie_ocr_une_operation_par_ligne(self):
        r = detecter_dettes_releve(ENTETE + RELEVE_OCR)
        self.assertEqual(r["mensualite"], 250.0)

    def test_releve_sans_credit_ne_trouve_rien_mais_le_releve_est_present(self):
        """Distinction capitale : « aucune dette détectée » n'est pas « dettes inconnues »."""
        r = detecter_dettes_releve(ENTETE + RELEVE_SANS_CREDIT)
        self.assertTrue(r["releve_present"])
        self.assertTrue(r["exploitable"])        # des opérations datées ont été lues
        self.assertIsNone(r["mensualite"])
        self.assertEqual(r["details"], [])

    def test_sans_releve_dans_le_dossier(self):
        r = detecter_dettes_releve("=== FICHE_PAIE (paie.pdf) ===\nBULLETIN DE PAIE\nNET A PAYER 2 100,000")
        self.assertFalse(r["releve_present"])
        self.assertIsNone(r["mensualite"])

    def test_texte_vide(self):
        self.assertFalse(detecter_dettes_releve("")["releve_present"])
        self.assertFalse(detecter_dettes_releve(None)["releve_present"])

    def test_releve_reconnu_par_son_contenu_meme_sans_titre_de_type(self):
        """Le client a déposé le relevé sous le type « AUTRE » : le contenu suffit."""
        texte = "=== AUTRE (scan.pdf) ===\n" + RELEVE_OCR
        self.assertEqual(detecter_dettes_releve(texte)["mensualite"], 250.0)

    def test_pret_solde_depuis_longtemps_n_est_pas_compte(self):
        """Le prêt n'apparaît qu'au premier mois d'un relevé de 4 mois : il est fini."""
        texte = ENTETE + """RELEVÉ DE COMPTE
05/05/2026 PRELEVEMENT ECHEANCE PRET CONSOMMATION 250,000 3 000,000
05/06/2026 RETRAIT DAB 100,000 2 900,000
05/07/2026 RETRAIT DAB 100,000 2 800,000
05/08/2026 RETRAIT DAB 100,000 2 700,000
"""
        self.assertIsNone(detecter_dettes_releve(texte)["mensualite"])

    def test_deux_prets_le_meme_mois_sont_additionnes(self):
        texte = ENTETE + """RELEVÉ DE COMPTE
05/09/2026 PRELEVEMENT ECHEANCE PRET CONSOMMATION 250,000 3 000,000
10/09/2026 PRELEVEMENT ECHEANCE PRET AUTO 400,500 2 600,000
"""
        self.assertEqual(detecter_dettes_releve(texte)["mensualite"], 650.5)

    def test_accents_et_montants_avec_espaces(self):
        texte = ENTETE + """RELEVÉ DE COMPTE
05/09/2026 Prélèvement échéance prêt immobilier 1 250,000 9 000,000
"""
        self.assertEqual(detecter_dettes_releve(texte)["mensualite"], 1250.0)

    def test_salaire_et_totaux_ne_sont_pas_des_dettes(self):
        texte = ENTETE + """RELEVÉ DE COMPTE
03/09/2026 VIREMENT SALAIRE REMBOURSEMENT PRET 2 100,000 4 385,550
Total des crédits du trimestre 6 300,000
"""
        self.assertIsNone(detecter_dettes_releve(texte)["mensualite"])

    def test_dates_illisibles_estimation_marquee_approximative(self):
        texte = ENTETE + """RELEVÉ DE COMPTE
PRELEVEMENT ECHEANCE PRET CONSOMMATION 250,000
PRELEVEMENT ECHEANCE PRET CONSOMMATION 250,000
"""
        # Sans date, aucune « opération » n'est identifiable : on ne devine pas, et surtout
        # on n'en conclut PAS « aucune dette » : le relevé est marqué non exploitable
        r = detecter_dettes_releve(texte)
        self.assertTrue(r["releve_present"])
        self.assertFalse(r["exploitable"])
        self.assertIsNone(r["mensualite"])

    def test_texte_apres_le_tableau_n_est_pas_une_operation(self):
        """Une phrase de pied de page qui parle de « prélèvement » et « crédit » ne compte pas."""
        texte = ENTETE + """RELEVÉ DE COMPTE
06/09/2026 RETRAIT DAB 200,000 3 935,550
NOUVEAU SOLDE au 30/09/2026 3 935,550 DT
Aucun prélèvement de crédit rejeté, aucun incident 100,000
"""
        self.assertIsNone(detecter_dettes_releve(texte)["mensualite"])


if __name__ == "__main__":
    unittest.main()
