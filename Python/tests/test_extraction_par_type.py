"""
Tests de l'extraction JSON adaptée au type de document (services/groq_service.py).

Lancer depuis le dossier Python/ :
    python -m unittest tests.test_extraction_par_type -v
"""
import json
import sys
import types
import unittest
from dataclasses import dataclass
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

# Faux routeur LLM, installé seulement s'il n'est pas déjà importé (un autre test peut l'avoir fait)
_stub = types.ModuleType("services.llm_client")
_stub.chat_completion = lambda *a, **k: None
_stub.LLMUnavailableError = type("LLMUnavailableError", (RuntimeError,), {})
_stub.etat = lambda: {}
sys.modules.setdefault("services.llm_client", _stub)

from services import groq_service  # noqa: E402
from services.groq_service import GroqService, schema_pour_type, consigne_pour_type  # noqa: E402


@dataclass
class LLMResponse:
    content: str
    provider: str
    model: str
    duree_ms: float


def champs(schema: str) -> set:
    """Noms des champs d'un schéma (une ligne « "nom": ... » par champ)."""
    return {ligne.split('"')[1] for ligne in schema.splitlines() if ligne.strip().startswith('"')}


def reponse_llm(contenu: dict) -> LLMResponse:
    return LLMResponse(content=json.dumps(contenu), provider="test", model="m", duree_ms=1.0)


class SchemaParTypeTest(unittest.TestCase):

    def test_cin_ne_demande_que_l_identite(self):
        self.assertEqual(champs(schema_pour_type("CIN")), {"nomClient", "prenomClient", "cin"})

    def test_fiche_de_paie_demande_le_revenu_et_pas_le_solde(self):
        c = champs(schema_pour_type("FICHE_PAIE"))
        self.assertIn("revenuMensuelNet", c)
        self.assertIn("employeur", c)
        self.assertNotIn("soldeMoyenCompte", c)
        self.assertNotIn("montantCredit", c)

    def test_releve_demande_les_charges_et_les_incidents(self):
        c = champs(schema_pour_type("RELEVE_BANCAIRE"))
        self.assertTrue({"chargesMensuelles", "incidentsPayment", "soldeMoyenCompte"} <= c)
        self.assertNotIn("revenuMensuelNet", c)

    def test_identite_toujours_demandee(self):
        for type_document in ("CIN", "FICHE_PAIE", "RELEVE_BANCAIRE", "ATTESTATION_EMPLOI", "JUSTIFICATIF_DOMICILE"):
            self.assertTrue({"nomClient", "prenomClient", "cin"} <= champs(schema_pour_type(type_document)),
                            type_document)

    def test_type_inconnu_ou_absent_garde_le_schema_complet(self):
        for type_document in (None, "", "AUTRE", "CONTRAT_TRAVAIL", "N_IMPORTE_QUOI"):
            self.assertEqual(schema_pour_type(type_document), groq_service.JSON_SCHEMA, type_document)

    def test_casse_ignoree(self):
        self.assertEqual(schema_pour_type("cin"), schema_pour_type("CIN"))

    def test_consigne_par_type(self):
        self.assertIn("net à payer", consigne_pour_type("FICHE_PAIE"))
        self.assertIn("échéances de crédit", consigne_pour_type("RELEVE_BANCAIRE"))
        self.assertEqual(consigne_pour_type("AUTRE"), "")
        self.assertEqual(consigne_pour_type(None), "")


class PromptEnvoyeTest(unittest.TestCase):
    """Le type classé arrive bien jusqu'au prompt envoyé au LLM."""

    IDENTITE = {"nomClient": "Ben Ali", "prenomClient": "Sami", "cin": "12015060"}

    def extraire(self, texte, type_document):
        envoyes = []

        def faux_llm(task, messages, **kwargs):
            envoyes.append(messages[-1]["content"])
            return reponse_llm(self.IDENTITE)

        service = GroqService()
        service._cache.clear()
        with mock.patch.object(groq_service, "chat_completion", faux_llm):
            resultat = service.extraire_json(texte, cin="12015060", type_document=type_document)
        return resultat, envoyes

    def test_le_type_classe_modifie_le_prompt(self):
        resultat, prompts = self.extraire("Bulletin de paie net à payer 2100,000 " * 3, "FICHE_PAIE")
        self.assertEqual(resultat["statut"], "SUCCESS")
        self.assertIn("identifié comme : FICHE_PAIE", prompts[0])
        self.assertIn("revenuMensuelNet", prompts[0])
        self.assertNotIn("soldeMoyenCompte", prompts[0])

    def test_sans_type_le_prompt_reste_generique(self):
        _, prompts = self.extraire("Un document quelconque avec assez de texte pour l'extraction. " * 2, None)
        self.assertNotIn("identifié comme", prompts[0])
        self.assertIn("soldeMoyenCompte", prompts[0])

    def test_un_meme_texte_classe_differemment_n_utilise_pas_le_meme_cache(self):
        service = GroqService()
        service._cache.clear()
        appels = []

        def faux_llm(task, messages, **kwargs):
            appels.append(1)
            return reponse_llm(self.IDENTITE)

        texte = "Texte ambigu assez long pour passer le seuil minimal. " * 3
        with mock.patch.object(groq_service, "chat_completion", faux_llm):
            service.extraire_json(texte, type_document="FICHE_PAIE")
            service.extraire_json(texte, type_document="RELEVE_BANCAIRE")
        self.assertEqual(len(appels), 2)


if __name__ == "__main__":
    unittest.main()
