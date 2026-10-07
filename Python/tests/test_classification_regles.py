"""
Tests de la classification par règles (services/classification_regles.py) et de son rôle
de premier niveau dans la cascade (services/document_classifier_service.py).

Lancer depuis le dossier Python/ :
    python -m unittest tests.test_classification_regles -v
"""
import sys
import types
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

# Faux routeur LLM, installé seulement s'il n'est pas déjà importé (un autre test peut l'avoir fait)
_stub = types.ModuleType("services.llm_client")
_stub.chat_completion = lambda *a, **k: None
_stub.LLMUnavailableError = type("LLMUnavailableError", (RuntimeError,), {})
_stub.etat = lambda: {}
sys.modules.setdefault("services.llm_client", _stub)

from services.classification_regles import classer_par_regles, normaliser  # noqa: E402
from services.controle_type import evaluer_type  # noqa: E402

CIN = """République Tunisienne
CARTE D'IDENTITÉ NATIONALE
N° 12015060
Nom : TRABELSI   Prénom : Yassine
Date de naissance : 14/03/1990   Lieu de naissance : Tunis
"""

CIN_ARABE = """الجمهورية التونسية
بطاقة التعريف الوطنية
رقم 12015060
اللقب الطرابلسي  الاسم ياسين
تاريخ الولادة 14/03/1990  مكان الولادة تونس
"""

FICHE_PAIE = """Exemple Tech SARL
BULLETIN DE PAIE - Septembre 2026
Matricule 0412   Salaire de base 2 400,000
Cotisations CNSS 220,000   Retenue IRPP 80,000
Salaire brut 2 400,000
NET A PAYER 2 100,000 DT
"""

RELEVE = """RELEVÉ DE COMPTE
Période : du 01/07/2026 au 30/09/2026
Date Libellé Débit Crédit Solde
01/09/2026 ANCIEN SOLDE 2 285,550
03/09/2026 VIREMENT SALAIRE 2 100,000 4 385,550
05/09/2026 PRELEVEMENT ECHEANCE PRET 250,000 4 135,550
"""

ATTESTATION = """Exemple Tech SARL - Tunis, le 01/10/2026
ATTESTATION DE TRAVAIL
Nous soussignés, Exemple Tech SARL, attestons par la présente que Monsieur Yassine TRABELSI,
né le 14/03/1990 à Tunis, titulaire de la carte d'identité nationale n° 12015060, est employé
au sein de notre société en qualité d'Ingénieur développement depuis le 01/09/2021.
"""

FACTURE = """STEG - Société Tunisienne de l'Electricité et du Gaz
FACTURE D'ELECTRICITE
Adresse : 12 rue de la Liberté, Tunis
Consommation 412 kWh   Echéance 20/10/2026
"""

# Attestation de RÉUSSITE d'une école : ce n'est pas une attestation de travail
ATTESTATION_ECOLE = """République Tunisienne
Ministère de l'Enseignement Supérieur
INSTITUT SUPERIEUR DES ETUDES TECHNOLOGIQUES
ATTESTATION DE REUSSITE 2022 - 2023
Le directeur atteste que l'étudiante Nom : REHOUMA Prénom : MERYEM
Titulaire de la Carte d'Identité Nationale N° est inscrite sous le numéro T12020
"""


def type_de(texte):
    verdict = classer_par_regles(texte)
    return verdict["type_document"] if verdict else None


class ClassificationReglesTest(unittest.TestCase):

    def test_documents_en_francais(self):
        self.assertEqual(type_de(CIN), "CIN")
        self.assertEqual(type_de(FICHE_PAIE), "FICHE_PAIE")
        self.assertEqual(type_de(RELEVE), "RELEVE_BANCAIRE")
        self.assertEqual(type_de(ATTESTATION), "ATTESTATION_EMPLOI")
        self.assertEqual(type_de(FACTURE), "JUSTIFICATIF_DOMICILE")

    def test_document_en_arabe(self):
        self.assertEqual(type_de(CIN_ARABE), "CIN")

    def test_texte_ocr_sans_accents_et_en_majuscules(self):
        self.assertEqual(type_de(RELEVE.upper().replace("É", "E")), "RELEVE_BANCAIRE")
        self.assertEqual(type_de(FICHE_PAIE.upper()), "FICHE_PAIE")

    def test_une_attestation_qui_cite_la_carte_d_identite_n_est_pas_une_cin(self):
        # c'est l'erreur que font les embeddings sur ce document
        self.assertEqual(type_de(ATTESTATION), "ATTESTATION_EMPLOI")

    def test_un_releve_qui_mentionne_une_facture_n_est_pas_une_facture(self):
        releve = RELEVE + "12/09/2026 PAIEMENT FACTURE STEG 85,000 4 050,550\n"
        self.assertEqual(type_de(releve), "RELEVE_BANCAIRE")

    def test_attestation_de_reussite_n_est_pas_une_attestation_de_travail(self):
        # les règles s'abstiennent (None) : la cascade passera aux embeddings puis au LLM
        self.assertNotEqual(type_de(ATTESTATION_ECOLE), "ATTESTATION_EMPLOI")
        self.assertNotEqual(type_de(ATTESTATION_ECOLE), "CIN")

    def test_abstention_sur_texte_court_vide_ou_sans_rapport(self):
        for texte in (None, "", "   ", "trop court", "Le chat dort sur le canapé du salon toute la journée."):
            self.assertIsNone(classer_par_regles(texte), texte)

    def test_les_regles_ne_repondent_jamais_autre(self):
        for texte in (ATTESTATION_ECOLE, "Bonjour, voici un texte quelconque sans aucun rapport avec la banque."):
            verdict = classer_par_regles(texte)
            self.assertTrue(verdict is None or verdict["type_document"] != "AUTRE")

    def test_le_verdict_a_la_meme_forme_que_les_autres_classifieurs(self):
        verdict = classer_par_regles(FICHE_PAIE)
        self.assertEqual(verdict["methode"], "regles")
        self.assertGreaterEqual(verdict["confiance"], 0.8)
        self.assertIn("mots_cles", verdict)

    def test_normalisation(self):
        self.assertEqual(normaliser("Relevé  d'Identité — N°"), "releve d identite n")
        self.assertEqual(normaliser("بطاقة"), normaliser("بِطَاقَة"))   # voyelles arabes ignorées

    def test_un_verdict_de_regles_est_fiable_pour_le_controle_de_type(self):
        verdict = classer_par_regles(RELEVE)
        controle = evaluer_type("FICHE_PAIE", verdict)
        self.assertTrue(controle["fiable"])
        self.assertFalse(controle["concordant"])       # relevé déposé comme fiche de paie
        self.assertEqual(controle["typeRetenu"], "RELEVE_BANCAIRE")


class CascadeTest(unittest.TestCase):
    """Les règles sont le premier niveau : quand elles tranchent, rien d'autre n'est appelé."""

    def creer(self, embeddings_confiance=0.40):
        from services.document_classifier_service import DocumentClassifierService

        appels = {"embeddings": 0, "llm": 0}

        class FauxEmbeddings:
            def classify(self_, texte, dossier_id=None):
                appels["embeddings"] += 1
                return {"type_document": "CIN", "confiance": embeddings_confiance, "top3": [], "alertes": []}

        class FauxLLM:
            def classify(self_, texte, dossier_id=None):
                appels["llm"] += 1
                return {"type_document": "AUTRE", "confiance": 0.9, "echec": False}

        service = object.__new__(DocumentClassifierService)   # sans charger les vrais modèles
        service.embeddings_classifier = FauxEmbeddings()
        service.llm_classifier = FauxLLM()
        return service, appels

    def test_regles_nettes_aucun_modele_appele(self):
        service, appels = self.creer()
        resultat = service.classify(RELEVE)
        self.assertEqual(resultat["type_document"], "RELEVE_BANCAIRE")
        self.assertEqual(resultat["methode"], "regles")
        self.assertEqual(appels, {"embeddings": 0, "llm": 0})

    def test_regles_abstentives_la_cascade_continue(self):
        service, appels = self.creer(embeddings_confiance=0.40)   # zone grise : le LLM tranche
        resultat = service.classify(ATTESTATION_ECOLE)
        self.assertEqual(appels["embeddings"], 1)
        self.assertEqual(appels["llm"], 1)
        self.assertEqual(resultat["methode"], "llm_fallback")


if __name__ == "__main__":
    unittest.main()
