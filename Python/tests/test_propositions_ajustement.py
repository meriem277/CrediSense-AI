"""
Tests des propositions d'ajustement pour un dossier CONDITIONNEL (services/agent_service.proposer_ajustements).

Le calcul est déterministe : on revérifie chaque offre avec les mêmes formules que la décision.

Lancer depuis le dossier Python/ :
    python -m unittest tests.test_propositions_ajustement -v
"""
import json
import os
import sys
import types
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

_stub = types.ModuleType("services.llm_client")
_stub.chat_completion = lambda *a, **k: None
_stub.LLMUnavailableError = type("LLMUnavailableError", (RuntimeError,), {})
_stub.etat = lambda: {}
sys.modules.setdefault("services.llm_client", _stub)

from services import agent_service  # noqa: E402
from services.agent_service import (  # noqa: E402
    AgentService, DTI_ACCEPTABLE, DUREE_MAX_MOIS, MULTIPLE_SALAIRE, calculer_mensualite, proposer_ajustements,
)

TAUX = 0.10


def metriques(**kw):
    base = {"monthlyIncome": 2000.0, "requestedAmount": 10000.0, "duration": 12, "existingDebts": 0.0,
            "clientAge": 36}
    base.update(kw)
    return base


def ok(critere):
    return {"criterion": critere, "status": "OK"}


def ko(critere):
    return {"criterion": critere, "status": "KO"}


class TestPropositionsAjustement(unittest.TestCase):

    def verifier_offre(self, o, m):
        """Une offre doit respecter, recalculée à la main, tous les contrôles ajustables."""
        revenu, dettes = m["monthlyIncome"], m["existingDebts"]
        mens = calculer_mensualite(o["amount"], o["duration"], TAUX)
        self.assertAlmostEqual(o["monthlyPayment"], mens, places=2)
        self.assertLess((mens + dettes) / revenu * 100, DTI_ACCEPTABLE, "endettement sous le seuil")
        self.assertAlmostEqual(o["dti"], (mens + dettes) / revenu * 100, places=1)
        self.assertLessEqual(o["amount"], MULTIPLE_SALAIRE * revenu, "plafond de 5 × salaire")
        self.assertLessEqual(o["duration"], DUREE_MAX_MOIS)
        self.assertGreaterEqual(o["amount"], agent_service.MONTANT_MIN)
        self.assertEqual(o["amount"] % agent_service.PAS_MONTANT, 0, "montant arrondi au pas inférieur")
        self.assertAlmostEqual(o["totalCost"], mens * o["duration"], places=1)

    def test_endettement_trop_haut_propose_montant_reduit_et_duree_allongee(self):
        m = metriques()                       # 10 000 DT sur 12 mois : 43,9 % d'endettement
        r = proposer_ajustements(m, TAUX, True, [ok("Taux d'endettement")])

        self.assertTrue(r["applicable"])
        par_genre = {o["kind"]: o for o in r["offers"]}
        self.assertEqual(set(par_genre), {"MONTANT_REDUIT", "DUREE_ALLONGEE"})
        for o in r["offers"]:
            self.verifier_offre(o, m)

        reduit, allongee = par_genre["MONTANT_REDUIT"], par_genre["DUREE_ALLONGEE"]
        self.assertLess(reduit["amount"], 10000)
        self.assertEqual(reduit["duration"], 12)                       # même durée
        self.assertEqual(allongee["amount"], 10000)                    # même montant
        self.assertGreater(allongee["duration"], 12)
        self.assertEqual(allongee["duration"] % 6, 0)

    def test_la_duree_proposee_est_la_plus_courte_qui_convient(self):
        r = proposer_ajustements(metriques(), TAUX, True, [])
        n = next(o["duration"] for o in r["offers"] if o["kind"] == "DUREE_ALLONGEE")
        mens = calculer_mensualite(10000, n - 6, TAUX)
        self.assertGreaterEqual(mens / 2000 * 100, DTI_ACCEPTABLE, "la durée juste avant ne convient pas")

    def test_le_montant_reduit_est_le_plus_haut_au_pas_pres(self):
        o = next(o for o in proposer_ajustements(metriques(), TAUX, True, [])["offers"]
                 if o["kind"] == "MONTANT_REDUIT")
        suivant = o["amount"] + agent_service.PAS_MONTANT
        self.assertGreaterEqual(calculer_mensualite(suivant, 12, TAUX) / 2000 * 100, DTI_ACCEPTABLE)

    def test_des_dettes_lourdes_donnent_une_offre_combinee(self):
        m = metriques(existingDebts=500.0)       # il ne reste que 100 DT/mois de capacité
        r = proposer_ajustements(m, TAUX, True, [])

        genres = [o["kind"] for o in r["offers"]]
        self.assertNotIn("DUREE_ALLONGEE", genres)        # même sur 84 mois, 10 000 DT ne passent pas
        self.assertIn("COMBINE", genres)
        combine = next(o for o in r["offers"] if o["kind"] == "COMBINE")
        self.assertEqual(combine["duration"], DUREE_MAX_MOIS)
        reduit = next(o for o in r["offers"] if o["kind"] == "MONTANT_REDUIT")
        self.assertGreater(combine["amount"], reduit["amount"])
        for o in r["offers"]:
            self.verifier_offre(o, m)

    def test_l_age_limite_la_duree_proposee(self):
        m = metriques(clientAge=67)               # 70 ans en fin de crédit : 36 mois au plus
        r = proposer_ajustements(m, TAUX, True, [])
        self.assertTrue(r["offers"])
        for o in r["offers"]:
            self.assertLessEqual(o["duration"], 36)
            self.verifier_offre(o, m)

    def test_age_depasse_aucune_offre(self):
        r = proposer_ajustements(metriques(clientAge=71), TAUX, True, [])
        self.assertFalse(r["applicable"])
        self.assertEqual(r["offers"], [])

    def test_montant_au_dessus_du_plafond_est_ramene_au_plafond_ou_moins(self):
        m = metriques(requestedAmount=15000.0, duration=84)    # plafond 10 000 DT, mais endettement correct
        r = proposer_ajustements(m, TAUX, True, [])
        self.assertTrue(r["applicable"])
        for o in r["offers"]:
            self.assertLessEqual(o["amount"], 10000)
        self.assertNotIn("DUREE_ALLONGEE", [o["kind"] for o in r["offers"]])   # à 84 mois, rien à allonger

    def test_rien_a_ajuster_quand_le_conditionnel_vient_d_ailleurs(self):
        m = metriques(requestedAmount=5000.0, duration=24)     # endettement correct
        r = proposer_ajustements(m, TAUX, True, [ko("Ancienneté dans l'emploi")])

        self.assertFalse(r["applicable"])
        self.assertEqual(r["offers"], [])
        self.assertIn("déjà respectés", r["message"])
        self.assertEqual(r["unresolved"], ["Ancienneté dans l'emploi"])

    def test_les_criteres_non_ajustables_sont_signales_meme_avec_des_offres(self):
        r = proposer_ajustements(metriques(), TAUX, True, [ko("Ancienneté dans l'emploi"),
                                                           {"criterion": "Type de contrat", "status": "ATTENTION"},
                                                           ko("Taux d'endettement")])
        self.assertTrue(r["applicable"])
        self.assertEqual(r["unresolved"], ["Ancienneté dans l'emploi", "Type de contrat"])
        self.assertIn("Ancienneté dans l'emploi", r["message"])
        self.assertNotIn("Taux d'endettement", r["unresolved"])

    def test_jamais_d_offre_sur_une_donnee_inconnue(self):
        cas = [proposer_ajustements(metriques(), None, True, []),
               proposer_ajustements(metriques(), TAUX, False, []),
               proposer_ajustements(metriques(monthlyIncome=None), TAUX, True, []),
               proposer_ajustements(metriques(duration=None), TAUX, True, [])]
        for r in cas:
            self.assertFalse(r["applicable"])
            self.assertEqual(r["offers"], [])
            self.assertTrue(r["message"])

    def test_dettes_qui_absorbent_toute_la_capacite(self):
        r = proposer_ajustements(metriques(existingDebts=700.0), TAUX, True, [])    # 700 > 30 % de 2 000
        self.assertFalse(r["applicable"])
        self.assertIn("dettes", r["message"].lower())

    def test_les_offres_respectent_toujours_les_controles(self):
        for revenu in (1500.0, 2000.0, 3000.0, 6000.0):
            for duree in (12, 24, 48):
                m = metriques(monthlyIncome=revenu, duration=duree)
                for o in proposer_ajustements(m, TAUX, True, [])["offers"]:
                    self.assertLessEqual(o["amount"], 10000)
                    self.verifier_offre(o, m)

    def test_le_message_dit_que_c_est_indicatif(self):
        r = proposer_ajustements(metriques(), TAUX, True, [])
        self.assertIn("indicatives", r["message"])
        self.assertIn("validation", r["message"])


class _Reponse:
    def __init__(self, contenu):
        self.content, self.provider, self.model = contenu, "faux", "faux"


DEMANDE = "=== DEMANDE DE CREDIT (formulaire client) ===\nMontant demande: 10000 TND\nDuree souhaitee: 12 mois\n\n"
PAIE = "=== FICHE_PAIE (paie.pdf) ===\nBULLETIN DE PAIE\nNET A PAYER 2 000,000 DT\n\n"
RELEVE = ("=== RELEVE_BANCAIRE (releve.pdf) ===\nRELEVÉ DE COMPTE\n"
          "03/09/2026 VIREMENT SALAIRE EXEMPLE TECH SARL 2 000,000 4 385,550\n"
          "06/09/2026 RETRAIT DAB 200,000 4 185,550\n")


class TestPropositionsDansLAnalyse(unittest.TestCase):

    def setUp(self):
        AgentService._cache.clear()
        p = patch.object(agent_service, "chat_completion", side_effect=self._llm)
        p.start()
        self.addCleanup(p.stop)
        self.eligibility = "CONDITIONNEL"
        self.montant = 10000.0

    def _llm(self, task, messages, **kwargs):
        return _Reponse(json.dumps({
            "eligibility": self.eligibility, "eligibilityScore": 55, "summary": "Dossier moyen.",
            "financialMetrics": {"monthlyIncome": 2000.0, "requestedAmount": self.montant, "duration": 12,
                                 "existingDebts": 0.0, "contractType": "CDI", "employmentStartDate": "01/09/2021",
                                 "paymentIncidents": 0, "clientAge": 36},
            "rawExplanation": "Analyse.", "risks": [], "recommendedPlan": []}))

    def analyser(self, taux="0.10"):
        with patch.dict(os.environ, {"CREDIT_TAUX_ANNUEL": taux}):
            return AgentService().analyser_consommation(DEMANDE + PAIE + RELEVE)

    def test_un_dossier_conditionnel_recoit_des_offres(self):
        r = self.analyser()
        self.assertEqual(r["eligibility"], "CONDITIONNEL")
        self.assertIn("adjustedOffers", r)
        self.assertTrue(r["adjustedOffers"]["applicable"])
        self.assertTrue(r["adjustedOffers"]["offers"])

    def test_pas_d_offre_pour_les_autres_decisions(self):
        self.eligibility, self.montant = "ELIGIBLE", 3000.0      # demande qui passe tous les contrôles
        r = self.analyser()
        self.assertEqual(r["eligibility"], "ELIGIBLE")
        self.assertNotIn("adjustedOffers", r)
        AgentService._cache.clear()
        self.eligibility = "REFUS"
        r = self.analyser()
        self.assertEqual(r["eligibility"], "REFUS")
        self.assertNotIn("adjustedOffers", r)

    def test_decision_a_completer_pas_d_offre(self):
        r = self.analyser(taux="")                 # taux manquant : A_COMPLETER
        self.assertEqual(r["eligibility"], "A_COMPLETER")
        self.assertNotIn("adjustedOffers", r)

    def test_la_version_des_regles_a_change(self):
        self.assertEqual(agent_service.RULES_VERSION, "2026-10-c")


if __name__ == "__main__":
    unittest.main()
