"""
Tests du fichier de paramètres du moteur de règles (services/parametres_regles.py et .json) :
chargement, validation, repli, statut « prototype », empreinte.

Lancer depuis le dossier Python/ :
    python -m unittest tests.test_parametres_regles -v
"""
import copy
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from services import parametres_regles as pr  # noqa: E402
from services.parametres_regles import ParametresInvalides, charger, verifier  # noqa: E402


def fichier_defaut() -> dict:
    return json.loads(pr.CHEMIN_DEFAUT.read_text(encoding="utf-8"))


class TestParametresRegles(unittest.TestCase):

    def ecrire(self, contenu) -> str:
        dossier = tempfile.mkdtemp()
        chemin = Path(dossier) / "p.json"
        chemin.write_text(contenu if isinstance(contenu, str) else json.dumps(contenu), encoding="utf-8")
        self.addCleanup(lambda: chemin.unlink(missing_ok=True))
        return str(chemin)

    # ── Le fichier fourni ────────────────────────────────────────────────────

    def test_le_fichier_fourni_est_valide(self):
        p = charger()
        self.assertTrue(p.fichier_valide)
        self.assertEqual(p.origine, "parametres_regles.json")
        verifier(fichier_defaut())          # ne lève pas

    def test_les_valeurs_du_fichier_sont_celles_du_prototype(self):
        p = charger()
        self.assertEqual((p.dti_acceptable, p.dti_max, p.multiple_salaire), (30.0, 35.0, 5.0))
        self.assertEqual((p.duree_max_mois, p.anciennete_min_mois, p.age_max_fin_credit), (84, 6, 70))
        self.assertEqual((p.score_min_eligible, p.plafond_score_si_ko), (60, 59))

    def test_les_valeurs_de_repli_sont_celles_du_fichier(self):
        """Le repli interne ne doit jamais diverger du fichier livré."""
        fichier = fichier_defaut()
        for nom in pr.SEUILS_REQUIS:
            self.assertEqual(pr.DEFAUTS["seuils"][nom]["valeur"], fichier["seuils"][nom]["valeur"], nom)
        self.assertEqual(pr.DEFAUTS["grille_score"], {k: fichier["grille_score"][k] for k in pr.DEFAUTS["grille_score"]})
        self.assertEqual(pr.DEFAUTS["decision_par_score"], {
            k: fichier["decision_par_score"][k] for k in pr.DEFAUTS["decision_par_score"]})

    def test_chaque_seuil_a_une_source_et_un_libelle(self):
        for nom, entree in fichier_defaut()["seuils"].items():
            self.assertTrue(entree.get("source"), nom)
            self.assertTrue(entree.get("libelle"), nom)
            self.assertIn("valide_par", entree, nom)
            self.assertIn("date_validation", entree, nom)

    # ── Statut : prototype tant que rien n'est validé ────────────────────────

    def test_par_defaut_le_statut_est_prototype(self):
        p = charger()
        self.assertEqual(p.statut, "PROTOTYPE")
        self.assertFalse(p.valide)
        self.assertEqual(sorted(p.seuils_non_valides), sorted(pr.SEUILS_REQUIS))
        resume = p.resume()
        self.assertEqual(resume["statut"], "PROTOTYPE")
        self.assertIn("valider", resume["avertissement"].lower())

    def test_le_statut_valide_exige_une_validation_signee_et_datee_de_chaque_seuil(self):
        brut = fichier_defaut()
        brut["statut"] = "VALIDE"
        # statut VALIDE mais aucune signature : reste prototype
        self.assertFalse(charger(self.ecrire(brut)).valide)

        for nom in pr.SEUILS_REQUIS:
            brut["seuils"][nom]["valide_par"] = "Direction des risques"
            brut["seuils"][nom]["date_validation"] = "2026-11-02"
        p = charger(self.ecrire(brut))
        self.assertTrue(p.valide)
        self.assertEqual(p.seuils_non_valides, [])
        self.assertEqual(p.resume()["statut"], "VALIDE")
        self.assertIsNone(p.resume()["avertissement"])

    def test_un_seul_seuil_non_signe_suffit_a_rester_prototype(self):
        brut = fichier_defaut()
        brut["statut"] = "VALIDE"
        for nom in pr.SEUILS_REQUIS:
            brut["seuils"][nom]["valide_par"] = "Direction des risques"
            brut["seuils"][nom]["date_validation"] = "2026-11-02"
        brut["seuils"]["dti_max"]["date_validation"] = None
        p = charger(self.ecrire(brut))
        self.assertFalse(p.valide)
        self.assertEqual(p.seuils_non_valides, ["dti_max"])

    def test_signatures_sans_statut_valide_restent_prototype(self):
        brut = fichier_defaut()
        for nom in pr.SEUILS_REQUIS:
            brut["seuils"][nom]["valide_par"] = "x"
            brut["seuils"][nom]["date_validation"] = "2026-11-02"
        self.assertFalse(charger(self.ecrire(brut)).valide)

    # ── Empreinte : un changement de valeur change la clé de cache ───────────

    def test_empreinte_stable_de_six_caracteres(self):
        a, b = charger(), charger()
        self.assertEqual(a.empreinte, b.empreinte)
        self.assertEqual(len(a.empreinte), 6)

    def test_empreinte_change_avec_un_seuil_ou_un_poids(self):
        base = charger().empreinte
        brut = fichier_defaut()
        brut["seuils"]["dti_max"]["valeur"] = 36
        self.assertNotEqual(charger(self.ecrire(brut)).empreinte, base)

        brut = fichier_defaut()
        brut["grille_score"]["poids"].update({"endettement": 30, "contrat": 30})
        self.assertNotEqual(charger(self.ecrire(brut)).empreinte, base)

    def test_empreinte_ignore_les_sources_et_les_commentaires(self):
        base = charger().empreinte
        brut = fichier_defaut()
        brut["seuils"]["dti_max"]["source"] = "Circulaire n° X"
        brut["avertissement"] = "autre texte"
        self.assertEqual(charger(self.ecrire(brut)).empreinte, base)

    # ── Validation ───────────────────────────────────────────────────────────

    def refuse(self, modifier, fragment: str):
        brut = fichier_defaut()
        modifier(brut)
        with self.assertRaises(ParametresInvalides) as e:
            verifier(brut)
        self.assertIn(fragment, str(e.exception))

    def test_seuils_inverses_refuses(self):
        self.refuse(lambda b: b["seuils"]["dti_acceptable"].update(valeur=40), "dti_acceptable")

    def test_endettement_maximum_au_dela_de_cent_refuse(self):
        self.refuse(lambda b: b["seuils"]["dti_max"].update(valeur=120), "100")

    def test_seuil_manquant_refuse(self):
        self.refuse(lambda b: b["seuils"].pop("duree_max_mois"), "duree_max_mois")

    def test_seuil_non_numerique_ou_negatif_refuse(self):
        self.refuse(lambda b: b["seuils"]["multiple_salaire"].update(valeur="cinq"), "multiple_salaire")
        self.refuse(lambda b: b["seuils"]["age_max_fin_credit"].update(valeur=-3), "age_max_fin_credit")
        self.refuse(lambda b: b["seuils"]["anciennete_min_mois"].update(valeur=0), "anciennete_min_mois")

    def test_poids_qui_ne_font_pas_cent_refuses(self):
        self.refuse(lambda b: b["grille_score"]["poids"].update(endettement=50), "100")

    def test_poids_manquant_ou_negatif_refuse(self):
        self.refuse(lambda b: b["grille_score"]["poids"].pop("incidents"), "incidents")
        self.refuse(lambda b: b["grille_score"]["poids"].update(montant=-10, endettement=60), "montant")

    def test_fraction_hors_de_zero_a_un_refusee(self):
        self.refuse(lambda b: b["grille_score"]["contrat"].update(CDI=1.5), "fraction")
        self.refuse(lambda b: b["grille_score"]["incidents"].update({"0": -0.2}), "fraction")
        self.refuse(lambda b: b["grille_score"]["anciennete"].update(paliers_mois_fraction=[[0, 0.0], [6, 2.0]]), "palier")

    def test_score_minimum_hors_echelle_refuse(self):
        self.refuse(lambda b: b["decision_par_score"].update(score_min_eligible=150), "score_min_eligible")

    def test_critere_de_grille_manquant_refuse(self):
        self.refuse(lambda b: b["grille_score"].pop("montant"), "montant")

    def test_contenu_qui_n_est_pas_un_objet_refuse(self):
        with self.assertRaises(ParametresInvalides):
            verifier([1, 2, 3])

    # ── Repli : le service ne s'arrête jamais ────────────────────────────────

    def test_fichier_absent_repli_sur_les_valeurs_internes(self):
        p = charger("/chemin/qui/n/existe/pas.json")
        self.assertFalse(p.fichier_valide)
        self.assertEqual(p.origine, "défaut interne")
        self.assertEqual((p.dti_acceptable, p.dti_max), (30.0, 35.0))
        self.assertFalse(p.valide)
        self.assertFalse(p.resume()["fichierValide"])

    def test_json_illisible_repli(self):
        p = charger(self.ecrire("{pas du json"))
        self.assertFalse(p.fichier_valide)
        self.assertEqual(p.dti_max, 35.0)

    def test_fichier_incoherent_repli(self):
        brut = fichier_defaut()
        brut["seuils"]["dti_acceptable"]["valeur"] = 50
        p = charger(self.ecrire(brut))
        self.assertFalse(p.fichier_valide)
        self.assertEqual(p.dti_acceptable, 30.0, "valeurs internes, pas celles du fichier incohérent")

    def test_charger_ne_leve_jamais(self):
        for contenu in ("", "null", "[]", "42", '{"seuils": 3}'):
            charger(self.ecrire(contenu))

    def test_le_repli_n_est_jamais_marque_valide(self):
        self.assertFalse(charger("/inexistant.json").valide)

    # ── Fichier indiqué par l'environnement ──────────────────────────────────

    def test_la_variable_d_environnement_designe_un_autre_fichier(self):
        brut = fichier_defaut()
        brut["seuils"]["dti_max"]["valeur"] = 38
        chemin = self.ecrire(brut)
        with patch.dict(os.environ, {"REGLES_PARAMETRES": chemin}):
            p = charger()
        self.assertTrue(p.fichier_valide)
        self.assertEqual(p.dti_max, 38.0)

    def test_une_variable_vide_utilise_le_fichier_livre(self):
        with patch.dict(os.environ, {"REGLES_PARAMETRES": ""}):
            self.assertEqual(charger().origine, "parametres_regles.json")

    def test_les_valeurs_du_fichier_modifient_le_comportement(self):
        from services.score_grille import calculer_score
        brut = fichier_defaut()
        brut["grille_score"]["poids"].update({"endettement": 60, "contrat": 10, "anciennete": 10, "incidents": 10, "montant": 10})
        p = charger(self.ecrire(brut))
        score = calculer_score(p, {"dti": 10, "contractType": "CDI", "paymentIncidents": 0,
                                   "monthlyIncome": 2000, "requestedAmount": 2000}, 120)
        self.assertEqual(next(c for c in score["criteres"] if c["id"] == "endettement")["maximum"], 60)
        self.assertEqual(score["total"], 100)

    def test_le_resume_ne_contient_que_des_valeurs_serialisables(self):
        json.dumps(charger().resume())
        json.dumps(charger("/inexistant.json").resume())


if __name__ == "__main__":
    unittest.main()
