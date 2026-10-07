"""
Tests du moteur de décision de l'agent de crédit (services/agent_service.py) :
taux d'intérêt obligatoire, dettes inconnues, décision « A_COMPLETER », cache.

Le LLM est simulé : on contrôle ce qu'il « répond » et on vérifie ce que les règles en font.

Lancer depuis le dossier Python/ :
    python -m unittest tests.test_agent_regles -v
"""
import json
import os
import sys
import types
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

# Faux routeur LLM, installé seulement s'il n'est pas déjà importé (un autre test peut l'avoir fait)
_stub = types.ModuleType("services.llm_client")
_stub.chat_completion = lambda *a, **k: None
_stub.LLMUnavailableError = type("LLMUnavailableError", (RuntimeError,), {})
_stub.etat = lambda: {}
sys.modules.setdefault("services.llm_client", _stub)

from services import agent_service  # noqa: E402
from services.agent_service import AgentService, _taux_annuel, calculer_mensualite  # noqa: E402

DEMANDE = "=== DEMANDE DE CREDIT (formulaire client) ===\nMontant demande: 9000 TND\nDuree souhaitee: 48 mois\n\n"
PAIE = "=== FICHE_PAIE (paie.pdf) ===\nBULLETIN DE PAIE\nNET A PAYER 2 100,000 DT\n\n"
RELEVE_AVEC_PRET = """=== RELEVE_BANCAIRE (releve.pdf) ===
RELEVÉ DE COMPTE
03/09/2026 VIREMENT SALAIRE EXEMPLE TECH SARL 2 100,000 4 385,550
05/09/2026 PRELEVEMENT ECHEANCE PRET CONSOMMATION N° 250,000 4 135,550
"""
RELEVE_SANS_PRET = """=== RELEVE_BANCAIRE (releve.pdf) ===
RELEVÉ DE COMPTE
03/09/2026 VIREMENT SALAIRE EXEMPLE TECH SARL 2 100,000 4 385,550
06/09/2026 RETRAIT DAB 200,000 4 185,550
"""

METRIQUES = {"monthlyIncome": 2100.0, "requestedAmount": 9000.0, "duration": 48,
             "existingDebts": None, "contractType": "CDI", "employmentStartDate": "01/09/2021",
             "paymentIncidents": 0, "clientAge": 36}


class _Reponse:
    def __init__(self, contenu):
        self.content, self.provider, self.model = contenu, "faux", "faux"


class TestAgentRegles(unittest.TestCase):

    def setUp(self):
        AgentService._cache.clear()
        self.appels = 0
        self.reponse = {}
        patcher = patch.object(agent_service, "chat_completion", side_effect=self._llm)
        patcher.start()
        self.addCleanup(patcher.stop)

    def _llm(self, task, messages, **kwargs):
        self.appels += 1
        return _Reponse(json.dumps(self.reponse))

    def analyser(self, texte, *, taux="0.10", metriques=None, eligibility="ELIGIBLE", score=75):
        self.reponse = {"eligibility": eligibility, "eligibilityScore": score, "summary": "Dossier solide.",
                        "financialMetrics": {**METRIQUES, **(metriques or {})},
                        "rawExplanation": "Analyse favorable.", "risks": [], "recommendedPlan": []}
        with patch.dict(os.environ, {"CREDIT_TAUX_ANNUEL": taux}):
            return AgentService().analyser_consommation(texte)

    # ── Lecture du taux ──────────────────────────────────────────────────────

    def test_lecture_du_taux(self):
        cas = {"0.10": 0.10, "10": 0.10, "10,5": 0.105, "0,085": 0.085, "": None,
               "abc": None, "-1": None, "0": None, "60": None, "1": None}
        for saisie, attendu in cas.items():
            with patch.dict(os.environ, {"CREDIT_TAUX_ANNUEL": saisie}):
                recu = _taux_annuel()
                if attendu is None:
                    self.assertIsNone(recu, f"« {saisie} » devrait être refusé")
                else:
                    self.assertAlmostEqual(recu, attendu, places=6, msg=f"« {saisie} »")

    # ── Taux d'intérêt obligatoire ───────────────────────────────────────────

    def test_sans_taux_la_decision_est_a_completer_et_rien_n_est_calcule(self):
        r = self.analyser(DEMANDE + PAIE + RELEVE_AVEC_PRET, taux="")

        self.assertEqual(r["eligibility"], "A_COMPLETER")
        self.assertIsNone(r["financialMetrics"]["monthlyPayment"])
        self.assertIsNone(r["financialMetrics"]["dti"])
        self.assertEqual(r["capacity"], {})
        self.assertEqual(r["simulations"], [])
        self.assertTrue(any("Taux d'intérêt" in m for m in r["donneesManquantes"]))
        self.assertIsNone(r["tauxAnnuelApplique"])

    def test_avec_taux_la_mensualite_inclut_les_interets_et_le_taux_d_endettement_est_calcule(self):
        r = self.analyser(DEMANDE + PAIE + RELEVE_AVEC_PRET)

        attendue = round(calculer_mensualite(9000, 48, 0.10), 3)
        self.assertGreater(attendue, 9000 / 48)                      # plus que sans intérêts
        self.assertEqual(r["financialMetrics"]["monthlyPayment"], attendue)
        self.assertAlmostEqual(r["financialMetrics"]["dti"], round((attendue + 250) / 2100 * 100, 2), places=2)
        self.assertEqual(r["eligibility"], "ELIGIBLE")
        self.assertEqual(r["donneesManquantes"], [])
        self.assertTrue(r["capacity"])
        self.assertTrue(any(s["isRequested"] for s in r["simulations"]))

    def test_le_taux_du_llm_n_est_jamais_utilise(self):
        """Le LLM propose sa propre mensualité et son propre DTI : ils sont écrasés par le calcul."""
        r = self.analyser(DEMANDE + PAIE + RELEVE_AVEC_PRET,
                          metriques={"monthlyPayment": 1.0, "dti": 1.0})
        self.assertNotEqual(r["financialMetrics"]["monthlyPayment"], 1.0)
        self.assertNotEqual(r["financialMetrics"]["dti"], 1.0)

    # ── Dettes ───────────────────────────────────────────────────────────────

    def test_dette_trouvee_dans_le_releve_quand_l_ia_ne_la_lit_pas(self):
        r = self.analyser(DEMANDE + PAIE + RELEVE_AVEC_PRET, metriques={"existingDebts": None})
        self.assertEqual(r["financialMetrics"]["existingDebts"], 250.0)
        self.assertIn("détectées dans le relevé", r["calculationNote"])

    def test_releve_lisible_sans_pret_dettes_nulles_et_dossier_non_bloque(self):
        r = self.analyser(DEMANDE + PAIE + RELEVE_SANS_PRET, metriques={"existingDebts": None})
        self.assertEqual(r["financialMetrics"]["existingDebts"], 0.0)
        self.assertEqual(r["eligibility"], "ELIGIBLE")
        self.assertIsNotNone(r["financialMetrics"]["dti"])

    def test_sans_releve_les_dettes_sont_inconnues_donc_a_completer(self):
        r = self.analyser(DEMANDE + PAIE, metriques={"existingDebts": None})
        self.assertEqual(r["eligibility"], "A_COMPLETER")
        self.assertIsNone(r["financialMetrics"]["dti"])                # pas de DTI sur une dette inconnue
        self.assertTrue(any("Dettes existantes" in m for m in r["donneesManquantes"]))

    def test_un_zero_de_l_ia_sans_aucun_releve_n_est_pas_une_preuve(self):
        r = self.analyser(DEMANDE + PAIE, metriques={"existingDebts": 0.0})
        self.assertEqual(r["eligibility"], "A_COMPLETER")

    def test_dette_positive_lue_par_l_ia_sans_releve_est_retenue(self):
        r = self.analyser(DEMANDE + PAIE, metriques={"existingDebts": 300.0})
        self.assertEqual(r["financialMetrics"]["existingDebts"], 300.0)
        self.assertNotIn("Dettes existantes", " ".join(r["donneesManquantes"]))

    def test_ecart_ia_releve_garde_la_valeur_la_plus_haute_et_avertit(self):
        r = self.analyser(DEMANDE + PAIE + RELEVE_AVEC_PRET, metriques={"existingDebts": 100.0})
        self.assertEqual(r["financialMetrics"]["existingDebts"], 250.0)
        self.assertTrue(any("Dettes existantes" in a for a in r["avertissements"]))

    # ── Décision « À COMPLÉTER » ─────────────────────────────────────────────

    def test_donnees_manquantes_remplacent_une_decision_favorable(self):
        for decision in ("ELIGIBLE", "CONDITIONNEL"):
            AgentService._cache.clear()
            r = self.analyser(DEMANDE + PAIE, eligibility=decision, score=82)
            self.assertEqual(r["eligibility"], "A_COMPLETER", decision)
            self.assertLessEqual(r["eligibilityScore"], 59)
            self.assertTrue(r["scoreProvisoire"])

    def test_le_texte_favorable_du_llm_n_est_plus_presente_comme_la_conclusion(self):
        r = self.analyser(DEMANDE + PAIE)
        self.assertIn("Décision impossible", r["summary"])
        self.assertIn("il manque", r["rawExplanation"])
        self.assertIn("relevé bancaire", r["rawExplanation"])
        self.assertEqual(r["analysePreliminaire"], "Analyse favorable.")   # conservée à part

    def test_le_message_destine_au_client_ne_revele_aucun_reglage_technique(self):
        """rawExplanation peut partir par e-mail au client : pas de « CREDIT_TAUX_ANNUEL » dedans."""
        r = self.analyser(DEMANDE + PAIE + RELEVE_AVEC_PRET, taux="")      # seul le taux manque
        self.assertEqual(r["eligibility"], "A_COMPLETER")
        self.assertNotIn("CREDIT_TAUX_ANNUEL", r["rawExplanation"])
        self.assertNotIn("service IA", r["rawExplanation"])
        self.assertIn("conseiller", r["rawExplanation"])
        # l'agent, lui, voit bien la cause réelle
        self.assertIn("CREDIT_TAUX_ANNUEL", " ".join(r["donneesManquantes"]))

    def test_un_refus_fonde_reste_un_refus(self):
        r = self.analyser(DEMANDE + PAIE, eligibility="REFUS", score=20)
        self.assertEqual(r["eligibility"], "REFUS")

    def test_dossier_complet_et_favorable_reste_eligible(self):
        r = self.analyser(DEMANDE + PAIE + RELEVE_AVEC_PRET)
        self.assertEqual(r["eligibility"], "ELIGIBLE")
        self.assertFalse(r.get("scoreProvisoire", False))

    def test_critere_bloquant_ko_rend_conditionnel_comme_avant(self):
        """Non-régression : 60 000 DT sur 12 mois dépasse largement 35 % d'endettement."""
        r = self.analyser(DEMANDE + PAIE + RELEVE_AVEC_PRET,
                          metriques={"requestedAmount": 60000.0, "duration": 12})
        self.assertEqual(r["eligibility"], "CONDITIONNEL")
        self.assertLessEqual(r["eligibilityScore"], 59)

    # ── Cache ────────────────────────────────────────────────────────────────

    def test_le_cache_ne_ressert_pas_une_analyse_faite_avant_la_configuration_du_taux(self):
        texte = DEMANDE + PAIE + RELEVE_AVEC_PRET
        premiere = self.analyser(texte, taux="")
        self.assertEqual(premiere["eligibility"], "A_COMPLETER")

        seconde = self.analyser(texte, taux="0.10")          # même dossier, taux maintenant configuré
        self.assertEqual(seconde["eligibility"], "ELIGIBLE")
        self.assertFalse(seconde["depuis_cache"])
        self.assertEqual(self.appels, 2)

    def test_meme_dossier_meme_taux_est_servi_depuis_le_cache(self):
        texte = DEMANDE + PAIE + RELEVE_AVEC_PRET
        self.analyser(texte)
        seconde = self.analyser(texte)
        self.assertTrue(seconde["depuis_cache"])
        self.assertEqual(self.appels, 1)


if __name__ == "__main__":
    unittest.main()
