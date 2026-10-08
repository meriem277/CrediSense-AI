"""
Tests de la grille de score (services/score_grille.py) et de son intégration dans le moteur de décision.

Le score est calculé par le code : même dossier, même note, détail par critère, jamais de donnée devinée.
Le modèle de langage est simulé : on vérifie que SON score n'influence plus le résultat.

Lancer depuis le dossier Python/ :
    python -m unittest tests.test_score_grille -v
"""
import json
import os
import random
import sys
import types
import unittest
from datetime import date
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

_stub = types.ModuleType("services.llm_client")
_stub.chat_completion = lambda *a, **k: None
_stub.LLMUnavailableError = type("LLMUnavailableError", (RuntimeError,), {})
_stub.etat = lambda: {}
sys.modules.setdefault("services.llm_client", _stub)

from services import agent_service  # noqa: E402
from services.agent_service import AgentService  # noqa: E402
from services.parametres_regles import charger  # noqa: E402
from services.score_grille import METHODE, calculer_score  # noqa: E402

P = charger()


def dossier(**kw):
    base = {"dti": 22.77, "contractType": "CDI", "paymentIncidents": 0, "monthlyIncome": 2100.0, "requestedAmount": 9000.0}
    base.update(kw)
    return base


def points(score, critere):
    return next(c for c in score["criteres"] if c["id"] == critere)["points"]


class TestGrilleDeScore(unittest.TestCase):

    # ── Structure ────────────────────────────────────────────────────────────

    def test_cinq_criteres_dont_les_maxima_font_cent(self):
        s = calculer_score(P, dossier(), 61)
        self.assertEqual([c["id"] for c in s["criteres"]], ["endettement", "contrat", "anciennete", "incidents", "montant"])
        self.assertEqual(sum(c["maximum"] for c in s["criteres"]), 100)
        self.assertEqual(s["methode"], METHODE)

    def test_le_total_est_la_somme_des_points_et_reste_entier(self):
        s = calculer_score(P, dossier(), 61)
        self.assertEqual(s["total"], sum(c["points"] for c in s["criteres"]))
        self.assertIsInstance(s["total"], int)
        self.assertFalse(s["provisoire"])

    def test_chaque_critere_explique_ses_points(self):
        for c in calculer_score(P, dossier(), 61)["criteres"]:
            self.assertTrue(c["libelle"])
            self.assertTrue(c["valeur"])
            self.assertTrue(c["explication"])
            self.assertTrue(c["connu"])

    def test_dossier_de_reference(self):
        s = calculer_score(P, dossier(), 61)
        self.assertEqual([points(s, c) for c in ("endettement", "contrat", "anciennete", "incidents", "montant")],
                         [36, 20, 15, 15, 5])
        self.assertEqual(s["total"], 91)

    def test_la_grille_est_marquee_prototype_tant_que_les_seuils_ne_sont_pas_valides(self):
        self.assertTrue(calculer_score(P, dossier(), 61)["prototype"])

    # ── Reproductibilité ─────────────────────────────────────────────────────

    def test_meme_dossier_meme_score(self):
        resultats = {json.dumps(calculer_score(P, dossier(), 30), sort_keys=True) for _ in range(200)}
        self.assertEqual(len(resultats), 1)

    def test_l_ordre_des_champs_n_a_aucune_influence(self):
        a = dossier()
        b = dict(reversed(list(a.items())))
        self.assertEqual(calculer_score(P, a, 40), calculer_score(P, b, 40))

    # ── Endettement ──────────────────────────────────────────────────────────

    def test_endettement_points_aux_bornes(self):
        self.assertEqual(points(calculer_score(P, dossier(dti=0), 61), "endettement"), 40)
        self.assertEqual(points(calculer_score(P, dossier(dti=20), 61), "endettement"), 40)
        self.assertEqual(points(calculer_score(P, dossier(dti=25), 61), "endettement"), 32)
        self.assertEqual(points(calculer_score(P, dossier(dti=30), 61), "endettement"), 24)
        self.assertEqual(points(calculer_score(P, dossier(dti=35), 61), "endettement"), 8)
        self.assertEqual(points(calculer_score(P, dossier(dti=40), 61), "endettement"), 0)
        self.assertEqual(points(calculer_score(P, dossier(dti=80), 61), "endettement"), 0)

    def test_plus_d_endettement_ne_donne_jamais_plus_de_points(self):
        precedent = 10**9
        for dti in [x / 2 for x in range(0, 121)]:                     # 0 à 60 %, par pas de 0,5
            p = points(calculer_score(P, dossier(dti=dti), 61), "endettement")
            self.assertLessEqual(p, precedent, f"dti={dti}")
            precedent = p

    def test_l_endettement_suit_les_seuils_du_fichier(self):
        brut = json.loads(Path(__file__).resolve().parent.parent.joinpath("services/parametres_regles.json").read_text(encoding="utf-8"))
        brut["seuils"]["dti_max"]["valeur"] = 45
        import tempfile
        chemin = Path(tempfile.mkdtemp()) / "p.json"
        chemin.write_text(json.dumps(brut), encoding="utf-8")
        p = charger(str(chemin))
        self.assertEqual(points(calculer_score(p, dossier(dti=45), 61), "endettement"), 8)

    # ── Contrat ──────────────────────────────────────────────────────────────

    def test_points_par_type_de_contrat(self):
        attendu = {"CDI": 20, "FONCTIONNAIRE": 20, "RETRAITE": 14, "CDD": 10, "INDEPENDANT": 8}
        for contrat, pts in attendu.items():
            self.assertEqual(points(calculer_score(P, dossier(contractType=contrat), 61), "contrat"), pts, contrat)

    def test_le_type_de_contrat_est_insensible_a_la_casse(self):
        self.assertEqual(points(calculer_score(P, dossier(contractType="cdi"), 61), "contrat"), 20)

    def test_contrat_inconnu_ou_non_reconnu_n_est_pas_devine(self):
        for valeur in (None, "", "STAGE"):
            s = calculer_score(P, dossier(contractType=valeur), 61)
            critere = next(c for c in s["criteres"] if c["id"] == "contrat")
            self.assertFalse(critere["connu"], valeur)
            self.assertIsNone(critere["points"])

    # ── Ancienneté ───────────────────────────────────────────────────────────

    def test_points_par_palier_d_anciennete(self):
        attendu = {0: 0, 5: 0, 6: 5, 11: 5, 12: 8, 23: 8, 24: 12, 59: 12, 60: 15, 200: 15}
        for mois, pts in attendu.items():
            self.assertEqual(points(calculer_score(P, dossier(), mois), "anciennete"), pts, f"{mois} mois")

    def test_anciennete_inconnue_n_est_pas_comptee_comme_zero(self):
        s = calculer_score(P, dossier(), None)
        critere = next(c for c in s["criteres"] if c["id"] == "anciennete")
        self.assertFalse(critere["connu"])
        self.assertIsNone(critere["points"])
        self.assertTrue(s["provisoire"])

    # ── Incidents ────────────────────────────────────────────────────────────

    def test_points_selon_les_incidents(self):
        self.assertEqual(points(calculer_score(P, dossier(paymentIncidents=0), 61), "incidents"), 15)
        self.assertEqual(points(calculer_score(P, dossier(paymentIncidents=1), 61), "incidents"), 5)
        self.assertEqual(points(calculer_score(P, dossier(paymentIncidents=2), 61), "incidents"), 0)
        self.assertEqual(points(calculer_score(P, dossier(paymentIncidents=9), 61), "incidents"), 0)

    def test_incidents_non_verifies_ne_valent_pas_zero_incident(self):
        s = calculer_score(P, dossier(paymentIncidents=None), 61)
        self.assertIsNone(next(c for c in s["criteres"] if c["id"] == "incidents")["points"])
        self.assertTrue(s["provisoire"])

    # ── Montant ──────────────────────────────────────────────────────────────

    def test_points_selon_le_rapport_montant_salaire(self):
        self.assertEqual(points(calculer_score(P, dossier(requestedAmount=2000, monthlyIncome=2000), 61), "montant"), 10)   # 1 ×
        self.assertEqual(points(calculer_score(P, dossier(requestedAmount=4000, monthlyIncome=2000), 61), "montant"), 10)   # 2 ×
        self.assertEqual(points(calculer_score(P, dossier(requestedAmount=10000, monthlyIncome=2000), 61), "montant"), 3)   # 5 × : plafond
        self.assertEqual(points(calculer_score(P, dossier(requestedAmount=12000, monthlyIncome=2000), 61), "montant"), 0)   # 6 ×

    def test_montant_ou_revenu_manquant_n_est_pas_devine(self):
        for modif in ({"requestedAmount": None}, {"monthlyIncome": None}, {"monthlyIncome": 0}):
            s = calculer_score(P, dossier(**modif), 61)
            self.assertIsNone(next(c for c in s["criteres"] if c["id"] == "montant")["points"], modif)

    # ── Données inconnues : score provisoire, jamais inventé ─────────────────

    def test_un_critere_inconnu_rend_le_score_provisoire_et_rapporte_aux_criteres_connus(self):
        s = calculer_score(P, dossier(dti=None), 61)
        self.assertTrue(s["provisoire"])
        connus = [c for c in s["criteres"] if c["connu"]]
        self.assertEqual(len(connus), 4)
        self.assertEqual(s["pointsConnus"], 60)                       # 100 − 40 (endettement écarté)
        self.assertEqual(s["pointsObtenus"], 20 + 15 + 15 + 5)
        self.assertEqual(s["total"], round(100 * 55 / 60))

    def test_aucune_donnee_connue_donne_zero_et_provisoire(self):
        s = calculer_score(P, {}, None)
        self.assertEqual(s["total"], 0)
        self.assertTrue(s["provisoire"])
        self.assertTrue(all(not c["connu"] for c in s["criteres"]))

    def test_une_donnee_inconnue_ne_baisse_pas_artificiellement_le_score(self):
        complet = calculer_score(P, dossier(), 61)["total"]
        sans_dti = calculer_score(P, dossier(dti=None), 61)["total"]
        self.assertGreaterEqual(sans_dti, complet - 40, "écarté, pas compté comme zéro")
        self.assertGreater(sans_dti, complet - 40 + 0)

    # ── Propriétés sur des dossiers au hasard ────────────────────────────────

    def test_le_score_reste_toujours_entre_zero_et_cent(self):
        hasard = random.Random(7)
        for _ in range(3000):
            s = calculer_score(P, {
                "dti": hasard.choice([None, hasard.uniform(0, 90)]),
                "contractType": hasard.choice([None, "CDI", "CDD", "INDEPENDANT", "RETRAITE", "FONCTIONNAIRE", "?"]),
                "paymentIncidents": hasard.choice([None, 0, 1, 2, 5]),
                "monthlyIncome": hasard.choice([None, 0, hasard.uniform(500, 9000)]),
                "requestedAmount": hasard.choice([None, 0, hasard.uniform(500, 80000)]),
            }, hasard.choice([None, hasard.randrange(0, 400)]))
            self.assertTrue(0 <= s["total"] <= 100, s["total"])
            self.assertEqual(s["provisoire"], any(not c["connu"] for c in s["criteres"]))

    def test_le_meilleur_dossier_possible_atteint_cent(self):
        s = calculer_score(P, dossier(dti=5, requestedAmount=2000), 120)
        self.assertEqual(s["total"], 100)

    def test_le_pire_dossier_possible_vaut_peu(self):
        s = calculer_score(P, dossier(dti=60, contractType="INDEPENDANT", paymentIncidents=5, requestedAmount=40000), 0)
        self.assertLessEqual(s["total"], 10)


# ── Intégration dans le moteur de décision ──────────────────────────────────

class _Reponse:
    def __init__(self, contenu):
        self.content, self.provider, self.model = contenu, "faux", "faux"


DEMANDE = "=== DEMANDE DE CREDIT (formulaire client) ===\nMontant demande: 9000 TND\nDuree souhaitee: 48 mois\n\n"
PAIE = "=== FICHE_PAIE (paie.pdf) ===\nBULLETIN DE PAIE\nNET A PAYER 2 100,000 DT\n\n"
RELEVE = ("=== RELEVE_BANCAIRE (releve.pdf) ===\nRELEVÉ DE COMPTE\n"
          "03/09/2026 VIREMENT SALAIRE EXEMPLE TECH SARL 2 100,000 4 385,550\n"
          "06/09/2026 RETRAIT DAB 200,000 4 185,550\n")


class TestScoreDansLeMoteur(unittest.TestCase):

    def setUp(self):
        AgentService._cache.clear()
        self.score_llm = 75
        self.eligibility = "ELIGIBLE"
        p = patch.object(agent_service, "chat_completion", side_effect=self._llm)
        p.start()
        self.addCleanup(p.stop)

    def _llm(self, task, messages, **kwargs):
        return _Reponse(json.dumps({
            "eligibility": self.eligibility, "eligibilityScore": self.score_llm, "summary": "Dossier.",
            "financialMetrics": {"monthlyIncome": 2100.0, "requestedAmount": 9000.0, "duration": 48, "existingDebts": 0.0,
                                 "contractType": "CDI", "employmentStartDate": "01/09/2021", "paymentIncidents": 0,
                                 "clientAge": 36},
            "rawExplanation": "Analyse.", "risks": [], "recommendedPlan": []}))

    def analyser(self, taux="0.10"):
        AgentService._cache.clear()
        with patch.dict(os.environ, {"CREDIT_TAUX_ANNUEL": taux}):
            return AgentService().analyser_consommation(DEMANDE + PAIE + RELEVE)

    def test_le_score_du_modele_n_influence_plus_le_resultat(self):
        scores = set()
        for score_llm in (10, 55, 78, 85, 99):
            self.score_llm = score_llm
            r = self.analyser()
            scores.add(r["eligibilityScore"])
            self.assertEqual(r["scoreLLMIndicatif"], score_llm, "gardé à titre indicatif seulement")
        self.assertEqual(len(scores), 1, "le même dossier donne toujours la même note")

    def test_le_resultat_porte_le_detail_du_score(self):
        r = self.analyser()
        detail = r["scoreDetail"]
        self.assertEqual(detail["methode"], "grille-v1")
        self.assertEqual(detail["total"], r["eligibilityScore"])
        self.assertEqual(len(detail["criteres"]), 5)
        self.assertEqual(sum(c["points"] for c in detail["criteres"]), r["eligibilityScore"])
        self.assertTrue(detail["prototype"])
        self.assertFalse(r["scoreProvisoire"])

    def test_le_resultat_indique_les_parametres_utilises(self):
        r = self.analyser()
        params = r["parametresRegles"]
        self.assertEqual(params["statut"], "PROTOTYPE")
        self.assertEqual(params["empreinte"], agent_service.PARAMETRES.empreinte)
        self.assertEqual(params["seuils"]["dti_max"], 35.0)
        self.assertTrue(params["fichierValide"])

    def test_le_score_correspond_au_dossier_de_reference(self):
        r = self.analyser()
        # 9 000 DT sur 48 mois à 10 % : 228,263 DT par mois, sans dette : 10,87 % d'endettement
        self.assertAlmostEqual(r["financialMetrics"]["dti"], 10.87, places=1)
        # la date d'embauche 01/09/2021 donne plus de 60 mois jusqu'à 2026 : palier maximum
        self.assertGreaterEqual(agent_service.anciennete_en_mois("01/09/2021"), 60)
        self.assertEqual([c["points"] for c in r["scoreDetail"]["criteres"]], [40, 20, 15, 15, 5])
        self.assertEqual(r["eligibilityScore"], 95)

    def test_la_version_des_regles_a_change(self):
        self.assertEqual(agent_service.RULES_VERSION, "2026-10-c")
        self.assertEqual(self.analyser()["versionRegles"], "2026-10-c")

    def test_un_critere_bloquant_en_echec_plafonne_le_score(self):
        self.eligibility = "CONDITIONNEL"
        with patch.object(agent_service, "PARAMETRES", agent_service.PARAMETRES):
            self.score_llm = 95
            # 9 000 DT sur 12 mois pour 2 100 DT de revenu : endettement au-delà du maximum
            def llm_court(task, messages, **kw):
                return _Reponse(json.dumps({
                    "eligibility": "ELIGIBLE", "eligibilityScore": 95, "summary": "x",
                    "financialMetrics": {"monthlyIncome": 2100.0, "requestedAmount": 9000.0, "duration": 12,
                                         "existingDebts": 0.0, "contractType": "CDI", "employmentStartDate": "01/09/2021",
                                         "paymentIncidents": 0, "clientAge": 36},
                    "rawExplanation": "x", "risks": [], "recommendedPlan": []}))
            with patch.object(agent_service, "chat_completion", side_effect=llm_court):
                AgentService._cache.clear()
                with patch.dict(os.environ, {"CREDIT_TAUX_ANNUEL": "0.10"}):
                    r = AgentService().analyser_consommation(DEMANDE.replace("48 mois", "12 mois") + PAIE + RELEVE)
        self.assertEqual(r["eligibility"], "CONDITIONNEL")
        self.assertLessEqual(r["eligibilityScore"], 59)

    def test_dossier_incomplet_score_provisoire_et_plafonne(self):
        r = self.analyser(taux="")                       # sans taux : dti et mensualité inconnus
        self.assertEqual(r["eligibility"], "A_COMPLETER")
        self.assertTrue(r["scoreProvisoire"])
        self.assertTrue(r["scoreDetail"]["provisoire"])
        self.assertLessEqual(r["eligibilityScore"], 59)
        endettement = next(c for c in r["scoreDetail"]["criteres"] if c["id"] == "endettement")
        self.assertFalse(endettement["connu"])

    def test_la_cle_de_cache_depend_des_parametres(self):
        """Changer un seuil ne doit jamais resservir une ancienne analyse."""
        premier = self.analyser()
        self.assertFalse(premier["depuis_cache"])
        with patch.dict(os.environ, {"CREDIT_TAUX_ANNUEL": "0.10"}):
            self.assertTrue(AgentService().analyser_consommation(DEMANDE + PAIE + RELEVE)["depuis_cache"])
            import copy
            import dataclasses
            brut = copy.deepcopy(agent_service.PARAMETRES.brut)
            brut["seuils"]["dti_max"]["valeur"] = 36
            autre = dataclasses.replace(agent_service.PARAMETRES, brut=brut)
            self.assertNotEqual(autre.empreinte, agent_service.PARAMETRES.empreinte)
            with patch.object(agent_service, "PARAMETRES", autre):
                self.assertFalse(AgentService().analyser_consommation(DEMANDE + PAIE + RELEVE)["depuis_cache"])


class TestEligibleSousLeScoreMinimum(unittest.TestCase):
    """_verifier_coherence : un score trop bas ne peut pas mener à « ELIGIBLE »."""

    def corriger(self, **resultat):
        base = {"eligibility": "ELIGIBLE", "eligibilityScore": 55, "scoreProvisoire": False, "regulatoryChecks": [], "risks": []}
        base.update(resultat)
        AgentService()._verifier_coherence(base)
        return base

    def test_eligible_sous_le_minimum_devient_conditionnel_avec_un_risque(self):
        r = self.corriger(eligibilityScore=55)
        self.assertEqual(r["eligibility"], "CONDITIONNEL")
        self.assertEqual(r["risks"][0]["source"], "Grille de score")
        self.assertIn("55/100", r["risks"][0]["description"])

    def test_au_minimum_la_decision_est_conservee(self):
        self.assertEqual(self.corriger(eligibilityScore=60)["eligibility"], "ELIGIBLE")
        self.assertEqual(self.corriger(eligibilityScore=91)["eligibility"], "ELIGIBLE")

    def test_un_score_provisoire_ne_change_pas_la_decision(self):
        self.assertEqual(self.corriger(eligibilityScore=30, scoreProvisoire=True)["eligibility"], "ELIGIBLE")

    def test_les_autres_decisions_ne_sont_pas_touchees(self):
        for decision in ("CONDITIONNEL", "REFUS", "A_COMPLETER"):
            self.assertEqual(self.corriger(eligibility=decision, eligibilityScore=10)["eligibility"], decision)

    def test_critere_bloquant_ko_plafonne_meme_une_decision_conditionnelle(self):
        r = self.corriger(eligibility="CONDITIONNEL", eligibilityScore=88,
                          regulatoryChecks=[{"criterion": "X", "status": "KO", "blocking": True}])
        self.assertEqual(r["eligibilityScore"], 59)
        self.assertEqual(r["eligibility"], "CONDITIONNEL")

    def test_un_critere_non_bloquant_ko_ne_plafonne_pas(self):
        r = self.corriger(eligibility="CONDITIONNEL", eligibilityScore=75,
                          regulatoryChecks=[{"criterion": "X", "status": "KO", "blocking": False}])
        self.assertEqual(r["eligibilityScore"], 75)


if __name__ == "__main__":
    unittest.main()
