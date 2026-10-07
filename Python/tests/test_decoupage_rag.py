"""
Tests du découpage des documents pour le RAG (services/decoupage_rag.py).

Aucun modèle téléchargé : les embeddings sont simulés (sac de mots déterministe).

Lancer depuis le dossier Python/ :
    python -m unittest tests.test_decoupage_rag -v
"""
import sys
import unittest
import zlib
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from services.decoupage_rag import MAX_CHARS_CHUNK, decouper_document, est_releve  # noqa: E402

EN_TETE_COLONNES = "Colonnes du relevé : Date | Libellé | Débit | Crédit | Solde"

# PDF natif : UNE CELLULE PAR LIGNE (date / libellé / montants)
RELEVE_NATIF = """RELEVÉ DE COMPTE
Période : du 01/07/2026 au 30/09/2026
Date
Libellé
Débit
Crédit
Solde
01/07/2026
ANCIEN SOLDE
1 150,000
03/07/2026
VIREMENT SALAIRE EXEMPLE TECH SARL
2 100,000
3 250,000
05/07/2026
PRELEVEMENT ECHEANCE PRET CONSOMMATION N°
2024-0457
250,000
3 000,000
NOUVEAU SOLDE
3 000,000
"""

# Texte d'OCR : une opération par ligne, dates sans année, un seul montant par ligne
RELEVE_OCR = """RELEVÉ DE COMPTE
Titulaire : Mme Sana TRABELSI
Ancien solde créditeur : 1 845,320
03/08 VIREMENT SALAIRE OMEGA SERVICES 2 280,788
06/08 RETRAIT DAB 300,000
18/08 ECHEANCE PRET AUTO 410,000
Nouveau solde créditeur : 3 336,458 DT
"""

CIN = """Document type CIN nom CIN.pdf :
République Tunisienne
CARTE D'IDENTITÉ NATIONALE
N° 12015060
Nom : TRABELSI   Prénom : Yassine
Date de naissance : 14/03/1990   Lieu de naissance : Tunis
"""


def embeddings_sac_de_mots(lignes):
    """Embeddings déterministes : deux lignes qui partagent des mots sont proches."""
    vecteurs = np.zeros((len(lignes), 64), dtype="float32")
    for i, ligne in enumerate(lignes):
        for mot in ligne.lower().split():
            vecteurs[i, zlib.crc32(mot.encode()) % 64] += 1.0
        vecteurs[i] /= np.linalg.norm(vecteurs[i]) or 1.0
    return vecteurs


class ReleveParOperationTest(unittest.TestCase):

    def setUp(self):
        self.chunks = decouper_document(RELEVE_NATIF)
        self.textes = [c["text"] for c in self.chunks]

    def test_un_chunk_par_operation_plus_en_tete_et_pied(self):
        # en-tête du relevé + 3 opérations (dont l'ancien solde) + pied de page
        self.assertEqual(len(self.chunks), 5)
        self.assertTrue(any("NOUVEAU SOLDE" in t and "COLONNES" not in t.upper() for t in self.textes))

    def test_chaque_operation_repete_l_en_tete_des_colonnes(self):
        operations = [t for t in self.textes if t.startswith(EN_TETE_COLONNES)]
        self.assertEqual(len(operations), 3)

    def test_une_operation_n_est_jamais_coupee(self):
        pret = next(t for t in self.textes if "PRELEVEMENT" in t)
        self.assertIn("2024-0457", pret)       # la ligne suivante du libellé
        self.assertIn("250,000", pret)         # le montant
        self.assertIn("3 000,000", pret)       # le solde

    def test_le_sens_credit_ou_debit_est_deduit_du_solde(self):
        salaire = next(t for t in self.textes if "VIREMENT SALAIRE" in t)
        pret    = next(t for t in self.textes if "PRELEVEMENT" in t)
        self.assertIn("sens déduit du solde : crédit", salaire)    # 1 150 + 2 100 = 3 250
        self.assertIn("sens déduit du solde : débit", pret)        # 3 250 - 250 = 3 000

    def test_l_ancien_solde_n_a_pas_de_sens(self):
        ancien = next(t for t in self.textes if "ANCIEN SOLDE" in t)
        self.assertNotIn("sens déduit", ancien)

    def test_les_chunks_de_releve_sont_de_type_bancaire(self):
        self.assertTrue(all(c["chunk_type"] == "bancaire" for c in self.chunks))

    def test_releve_ocr_une_ligne_par_operation(self):
        chunks = decouper_document(RELEVE_OCR)
        salaire = [c for c in chunks if "VIREMENT SALAIRE" in c["text"]]
        self.assertEqual(len(salaire), 1)
        self.assertNotIn("RETRAIT", salaire[0]["text"])            # une opération par chunk
        self.assertNotIn("sens déduit", salaire[0]["text"])        # un seul montant : sens inconnu

    def test_sens_non_deduit_si_le_solde_ne_colle_pas(self):
        texte = RELEVE_NATIF.replace("3 250,000", "9 999,000")      # solde incohérent
        salaire = next(c["text"] for c in decouper_document(texte) if "VIREMENT SALAIRE" in c["text"])
        self.assertNotIn("sens déduit", salaire)                   # on ne devine pas

    def test_detection_d_un_releve(self):
        self.assertTrue(est_releve([l for l in RELEVE_NATIF.splitlines() if l.strip()]))
        self.assertFalse(est_releve(["BULLETIN DE PAIE", "NET A PAYER 2 100,000 DT"]))
        # un titre sans opérations datées n'est pas exploitable comme relevé
        self.assertFalse(est_releve(["RELEVÉ DE COMPTE", "Titulaire : X"]))


class DocumentCourtTest(unittest.TestCase):

    def test_un_document_court_n_est_jamais_coupe(self):
        chunks = decouper_document(CIN)
        self.assertEqual(len(chunks), 1)
        self.assertIn("12015060", chunks[0]["text"])
        self.assertIn("Lieu de naissance", chunks[0]["text"])

    def test_la_ligne_d_etiquette_du_backend_n_est_pas_du_contenu(self):
        chunks = decouper_document(CIN)
        self.assertNotIn("Document type", chunks[0]["text"])

    def test_texte_vide(self):
        self.assertEqual(decouper_document(""), [])
        self.assertEqual(decouper_document("   \n  \n"), [])
        self.assertEqual(decouper_document(None), [])


class TexteLibreTest(unittest.TestCase):

    SUJET_SALAIRE = [
        "Le salaire de base du salarié est de deux mille dinars par mois net",
        "Le salaire brut inclut la prime de transport et la prime de rendement",
        "Les cotisations sociales sont déduites du salaire brut chaque mois",
        "Le net à payer correspond au salaire brut moins les cotisations sociales",
    ]
    SUJET_ADRESSE = [
        "Adresse du domicile : rue de la Liberté, Tunis, code postal 1002 immeuble",
        "Le locataire habite l'appartement du deuxième étage de cet immeuble résidentiel",
        "La résidence principale se situe dans le quartier de la rue de la Liberté",
        "Le bail de l'appartement indique la même adresse que celle de la rue de Tunis",
    ]

    def test_aucun_chunk_ne_depasse_la_taille_maximale(self):
        lignes = [f"ligne numéro {i} avec un peu de texte pour remplir {i * 7}" for i in range(60)]
        chunks = decouper_document("\n".join(lignes))
        self.assertGreater(len(chunks), 1)
        self.assertTrue(all(len(c["text"]) <= MAX_CHARS_CHUNK for c in chunks))

    def test_aucune_ligne_n_est_perdue(self):
        lignes = [f"ligne numéro {i} avec un peu de texte pour remplir {i * 7}" for i in range(60)]
        chunks = decouper_document("\n".join(lignes))
        conserve = "\n".join(c["text"] for c in chunks)
        self.assertTrue(all(l in conserve for l in lignes))

    def test_une_ligne_tres_longue_est_coupee_aux_mots(self):
        phrase = "Cette attestation est délivrée à l'intéressé pour servir et valoir ce que de droit. " * 20
        chunks = decouper_document(phrase)
        self.assertGreater(len(chunks), 1)
        self.assertTrue(all(len(c["text"]) <= MAX_CHARS_CHUNK for c in chunks))

    def test_coupure_au_changement_de_sujet(self):
        texte = "\n".join(self.SUJET_SALAIRE + self.SUJET_ADRESSE)
        chunks = decouper_document(texte, embed_fn=embeddings_sac_de_mots)

        self.assertGreater(len(chunks), 1)
        for chunk in chunks:
            lignes = chunk["text"].split("\n")
            sujets = {("salaire" if l in self.SUJET_SALAIRE else "adresse") for l in lignes}
            self.assertEqual(len(sujets), 1, f"un chunk mélange deux sujets : {lignes}")

    def test_sans_embeddings_on_coupe_quand_meme_sur_la_taille(self):
        texte = "\n".join(self.SUJET_SALAIRE + self.SUJET_ADRESSE)
        chunks = decouper_document(texte, embed_fn=None)
        self.assertTrue(all(len(c["text"]) <= MAX_CHARS_CHUNK for c in chunks))

    def test_embeddings_en_panne_ne_cassent_pas_le_decoupage(self):
        def en_panne(_):
            raise RuntimeError("modèle indisponible")
        texte = "\n".join(self.SUJET_SALAIRE + self.SUJET_ADRESSE)
        chunks = decouper_document(texte, embed_fn=en_panne)
        self.assertTrue(chunks)

    def test_le_type_du_chunk_vient_de_ses_ancres(self):
        def ancres(texte):
            return {"revenu": True} if "salaire" in texte.lower() else {}
        chunks = decouper_document("\n".join(self.SUJET_SALAIRE), ancres_fn=ancres)
        self.assertEqual(chunks[0]["chunk_type"], "revenu")
        self.assertEqual(chunks[0]["anchors"], ["revenu"])
        autres = decouper_document("Texte sans rapport avec la banque", ancres_fn=ancres)
        self.assertEqual(autres[0]["chunk_type"], "contexte")


if __name__ == "__main__":
    unittest.main()
