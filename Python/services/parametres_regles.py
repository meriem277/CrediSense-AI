"""
Paramètres du moteur de règles : seuils d'acceptation et grille de score.

Ils sont dans un fichier JSON (services/parametres_regles.json) et non dans le code : on les change sans
toucher à la logique, et chaque seuil porte sa SOURCE et sa DATE DE VALIDATION. Tant que la banque ne les
a pas validés, le statut reste « PROTOTYPE » : l'application le dit à l'agent.

Un fichier alternatif peut être indiqué avec la variable d'environnement REGLES_PARAMETRES.
Un fichier absent ou invalide ne bloque pas le service : les valeurs par défaut internes sont utilisées et
le résultat l'indique (« fichier_valide »: false).
"""
import copy
import hashlib
import json
import logging
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)

CHEMIN_DEFAUT = Path(__file__).with_name("parametres_regles.json")

# Mêmes valeurs que le fichier JSON : repli si le fichier est absent ou invalide.
DEFAUTS: dict = {
    "version": "defaut-interne",
    "statut": "PROTOTYPE",
    "avertissement": "Valeurs par défaut internes (fichier de paramètres absent ou invalide).",
    "seuils": {
        "dti_acceptable":      {"valeur": 30, "source": "défaut interne"},
        "dti_max":             {"valeur": 35, "source": "défaut interne"},
        "multiple_salaire":    {"valeur": 5,  "source": "défaut interne"},
        "duree_max_mois":      {"valeur": 84, "source": "défaut interne"},
        "anciennete_min_mois": {"valeur": 6,  "source": "défaut interne"},
        "age_max_fin_credit":  {"valeur": 70, "source": "défaut interne"},
    },
    "grille_score": {
        "poids": {"endettement": 40, "contrat": 20, "anciennete": 15, "incidents": 15, "montant": 10},
        "endettement": {"plein_jusqua_pct": 20, "fraction_au_seuil_acceptable": 0.6,
                        "fraction_au_seuil_max": 0.2, "nul_a_pct_au_dela_du_max": 5},
        "contrat": {"CDI": 1.0, "FONCTIONNAIRE": 1.0, "RETRAITE": 0.7, "CDD": 0.5, "INDEPENDANT": 0.4},
        "anciennete": {"paliers_mois_fraction": [[0, 0.0], [6, 0.33], [12, 0.5], [24, 0.8], [60, 1.0]]},
        "incidents": {"0": 1.0, "1": 0.33},
        "montant": {"plein_jusqua_multiple": 2, "fraction_au_plafond": 0.3},
    },
    "decision_par_score": {"score_min_eligible": 60, "plafond_score_si_critere_bloquant_ko": 59},
}

SEUILS_REQUIS = ("dti_acceptable", "dti_max", "multiple_salaire", "duree_max_mois",
                 "anciennete_min_mois", "age_max_fin_credit")
CRITERES_SCORE = ("endettement", "contrat", "anciennete", "incidents", "montant")


class ParametresInvalides(ValueError):
    """Le fichier de paramètres est lisible mais incohérent (poids qui ne font pas 100, seuils inversés…)."""


@dataclass(frozen=True)
class Parametres:
    brut: dict
    fichier_valide: bool
    origine: str

    # ── Seuils ───────────────────────────────────────────────────────────────
    def seuil(self, nom: str) -> float:
        return float(self.brut["seuils"][nom]["valeur"])

    @property
    def dti_acceptable(self) -> float:      return self.seuil("dti_acceptable")
    @property
    def dti_max(self) -> float:             return self.seuil("dti_max")
    @property
    def multiple_salaire(self) -> float:    return self.seuil("multiple_salaire")
    @property
    def duree_max_mois(self) -> int:        return int(self.seuil("duree_max_mois"))
    @property
    def anciennete_min_mois(self) -> int:   return int(self.seuil("anciennete_min_mois"))
    @property
    def age_max_fin_credit(self) -> int:    return int(self.seuil("age_max_fin_credit"))

    # ── Grille de score et décision ──────────────────────────────────────────
    @property
    def grille(self) -> dict:
        return self.brut["grille_score"]

    @property
    def score_min_eligible(self) -> int:
        return int(self.brut["decision_par_score"]["score_min_eligible"])

    @property
    def plafond_score_si_ko(self) -> int:
        return int(self.brut["decision_par_score"]["plafond_score_si_critere_bloquant_ko"])

    # ── Statut de validation ─────────────────────────────────────────────────
    @property
    def statut(self) -> str:
        return str(self.brut.get("statut", "PROTOTYPE"))

    @property
    def valide(self) -> bool:
        """Vrai seulement si le statut est VALIDE ET que chaque seuil a une validation datée et signée."""
        if self.statut != "VALIDE":
            return False
        return all(self.brut["seuils"][n].get("valide_par") and self.brut["seuils"][n].get("date_validation")
                   for n in SEUILS_REQUIS)

    @property
    def seuils_non_valides(self) -> list[str]:
        return [n for n in SEUILS_REQUIS
                if not (self.brut["seuils"][n].get("valide_par") and self.brut["seuils"][n].get("date_validation"))]

    @property
    def empreinte(self) -> str:
        """Six caractères qui changent dès qu'une valeur change : ils entrent dans la clé de cache."""
        valeurs = {"seuils": {n: self.brut["seuils"][n]["valeur"] for n in SEUILS_REQUIS},
                   "grille": self.brut["grille_score"], "decision": self.brut["decision_par_score"]}
        return hashlib.sha256(json.dumps(valeurs, sort_keys=True).encode("utf-8")).hexdigest()[:6]

    def resume(self) -> dict:
        """Ce que le résultat de l'analyse indique sur les paramètres utilisés."""
        return {
            "version": self.brut.get("version"),
            "statut": "VALIDE" if self.valide else "PROTOTYPE",
            "empreinte": self.empreinte,
            "origine": self.origine,
            "fichierValide": self.fichier_valide,
            "seuilsNonValides": self.seuils_non_valides,
            "seuils": {n: self.brut["seuils"][n]["valeur"] for n in SEUILS_REQUIS},
            "avertissement": None if self.valide else self.brut.get("avertissement"),
        }


# ── Validation ───────────────────────────────────────────────────────────────

def verifier(brut: dict) -> None:
    """Lève ParametresInvalides si les paramètres sont incohérents."""
    if not isinstance(brut, dict):
        raise ParametresInvalides("le fichier doit contenir un objet JSON")
    for section in ("seuils", "grille_score", "decision_par_score"):
        if not isinstance(brut.get(section), dict):
            raise ParametresInvalides(f"section manquante ou mal formée : {section}")
    seuils = brut["seuils"]
    for nom in SEUILS_REQUIS:
        entree = seuils.get(nom)
        if not isinstance(entree, dict) or "valeur" not in entree:
            raise ParametresInvalides(f"seuil manquant : {nom}")
        try:
            valeur = float(entree["valeur"])
        except (TypeError, ValueError):
            raise ParametresInvalides(f"seuil non numérique : {nom}")
        if valeur <= 0:
            raise ParametresInvalides(f"seuil à strictement positif attendu : {nom} = {valeur}")
    if float(seuils["dti_acceptable"]["valeur"]) >= float(seuils["dti_max"]["valeur"]):
        raise ParametresInvalides("dti_acceptable doit être inférieur à dti_max")
    if float(seuils["dti_max"]["valeur"]) > 100:
        raise ParametresInvalides("dti_max ne peut pas dépasser 100 %")

    grille = brut["grille_score"]
    poids = grille.get("poids")
    if not isinstance(poids, dict):
        raise ParametresInvalides("poids manquants")
    for critere in CRITERES_SCORE:
        if critere not in poids:
            raise ParametresInvalides(f"poids manquant : {critere}")
        if not isinstance(poids[critere], (int, float)) or poids[critere] < 0:
            raise ParametresInvalides(f"poids invalide : {critere}")
    if round(sum(poids[c] for c in CRITERES_SCORE), 6) != 100:
        raise ParametresInvalides(f"les poids doivent totaliser 100 (total : {sum(poids[c] for c in CRITERES_SCORE)})")
    for critere in CRITERES_SCORE:
        if not isinstance(grille.get(critere), dict):
            raise ParametresInvalides(f"paramètres de grille manquants : {critere}")

    # Toute fraction de points doit rester entre 0 et 1
    def fractions(objet):
        if isinstance(objet, dict):
            for v in objet.values():
                yield from fractions(v)
        elif isinstance(objet, list):
            for v in objet:
                yield from fractions(v)
        elif isinstance(objet, (int, float)):
            yield objet
    for nom in ("contrat", "incidents"):
        for f in fractions(grille[nom]):
            if not 0 <= f <= 1:
                raise ParametresInvalides(f"fraction hors de 0 à 1 dans {nom} : {f}")
    paliers = grille["anciennete"].get("paliers_mois_fraction")
    if not isinstance(paliers, list) or not paliers:
        raise ParametresInvalides("paliers d'ancienneté manquants")
    for palier in paliers:
        if (not isinstance(palier, list) or len(palier) != 2
                or not all(isinstance(v, (int, float)) for v in palier) or not 0 <= palier[1] <= 1):
            raise ParametresInvalides("palier d'ancienneté invalide")

    decision = brut["decision_par_score"]
    for cle in ("score_min_eligible", "plafond_score_si_critere_bloquant_ko"):
        if not isinstance(decision.get(cle), (int, float)) or not 0 <= decision[cle] <= 100:
            raise ParametresInvalides(f"{cle} doit être un score entre 0 et 100")


# ── Chargement ───────────────────────────────────────────────────────────────

def charger(chemin: Optional[str] = None) -> Parametres:
    """
    Lit le fichier de paramètres. Ne lève jamais : en cas de problème, les valeurs par défaut internes
    sont utilisées et `fichier_valide` vaut faux.
    """
    chemin_utilise = Path(chemin or os.getenv("REGLES_PARAMETRES", "") or CHEMIN_DEFAUT)
    try:
        brut = json.loads(chemin_utilise.read_text(encoding="utf-8"))
        verifier(brut)
        return Parametres(brut=brut, fichier_valide=True, origine=chemin_utilise.name)
    except FileNotFoundError:
        logger.error("Fichier de paramètres introuvable (%s) : valeurs par défaut internes utilisées", chemin_utilise)
    except (ParametresInvalides, json.JSONDecodeError, OSError, TypeError, KeyError, AttributeError, ValueError) as e:
        logger.error("Fichier de paramètres invalide (%s) : %s. Valeurs par défaut internes utilisées", chemin_utilise, e)
    return Parametres(brut=copy.deepcopy(DEFAUTS), fichier_valide=False, origine="défaut interne")
