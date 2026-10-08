"""
Score de crédit calculé par le code (grille à points), sans modèle de langage.

Pourquoi : un score donné par un modèle de langage change d'un appel à l'autre pour le même dossier
(mesuré : 78 puis 85) et ne s'explique pas. Ici le score est une SOMME DE POINTS par critère :
même dossier, même note, et le détail « 58 = 22 + 20 + 12 + 4 » est affiché à l'agent.

Cinq critères (poids par défaut, modifiables dans parametres_regles.json) :
  endettement 40 · contrat 20 · ancienneté 15 · incidents 15 · montant 10

Un critère dont la donnée est inconnue ne donne ni point ni zéro : il est écarté du calcul et le score est
marqué « provisoire » (rapporté aux seuls critères connus). On ne devine jamais une donnée.

PROTOTYPE : les poids et les paliers sont des choix de conception à valider avec la banque.
"""
from typing import Optional

from services.parametres_regles import Parametres

METHODE = "grille-v1"

LIBELLES = {
    "endettement": "Taux d'endettement",
    "contrat":     "Stabilité du contrat",
    "anciennete":  "Ancienneté dans l'emploi",
    "incidents":   "Incidents de paiement",
    "montant":     "Montant demandé / salaire",
}


def _interpoler(x: float, points: list[tuple[float, float]]) -> float:
    """Interpolation linéaire entre des points (x, fraction) triés ; constante au-delà des extrémités."""
    if x <= points[0][0]:
        return points[0][1]
    for (x0, y0), (x1, y1) in zip(points, points[1:]):
        if x <= x1:
            return y0 if x1 == x0 else y0 + (y1 - y0) * (x - x0) / (x1 - x0)
    return points[-1][1]


def _fraction_endettement(dti: float, p: Parametres) -> float:
    g = p.grille["endettement"]
    return _interpoler(dti, [
        (0.0, 1.0),
        (float(g["plein_jusqua_pct"]), 1.0),
        (p.dti_acceptable, float(g["fraction_au_seuil_acceptable"])),
        (p.dti_max, float(g["fraction_au_seuil_max"])),
        (p.dti_max + float(g["nul_a_pct_au_dela_du_max"]), 0.0),
    ])


def _fraction_contrat(contrat: str, p: Parametres) -> Optional[float]:
    return p.grille["contrat"].get((contrat or "").upper())


def _fraction_anciennete(mois: int, p: Parametres) -> float:
    fraction = 0.0
    for seuil, valeur in p.grille["anciennete"]["paliers_mois_fraction"]:
        if mois >= seuil:
            fraction = float(valeur)
    return fraction


def _fraction_incidents(incidents: int, p: Parametres) -> float:
    return float(p.grille["incidents"].get(str(int(incidents)), 0.0))


def _fraction_montant(rapport: float, p: Parametres) -> float:
    g = p.grille["montant"]
    plafond = p.multiple_salaire
    if rapport > plafond:
        return 0.0
    return _interpoler(rapport, [(0.0, 1.0), (float(g["plein_jusqua_multiple"]), 1.0),
                                 (plafond, float(g["fraction_au_plafond"]))])


def _pct(v: float) -> str:
    return f"{v:.2f}".replace(".", ",") + " %"


def calculer_score(p: Parametres, metrics: dict, anciennete_mois: Optional[int]) -> dict:
    """
    Retourne {"methode", "total", "provisoire", "prototype", "criteres": [...]}.

    `metrics` : dti (None si non calculable), monthlyIncome, requestedAmount, contractType, paymentIncidents.
    `anciennete_mois` : calculée par l'appelant à partir de la date d'embauche (None si inconnue).
    Chaque critère : {"id", "libelle", "points", "maximum", "connu", "valeur", "explication"}.
    """
    poids = p.grille["poids"]
    criteres = []

    def ajouter(identifiant: str, fraction: Optional[float], valeur: str, explication: str) -> None:
        maximum = int(round(float(poids[identifiant])))
        connu = fraction is not None
        criteres.append({
            "id": identifiant, "libelle": LIBELLES[identifiant], "maximum": maximum, "connu": connu,
            "points": int(round(fraction * maximum)) if connu else None,
            "valeur": valeur, "explication": explication,
        })

    # 1. Endettement
    dti = metrics.get("dti")
    if dti is None:
        ajouter("endettement", None, "inconnu", "Taux d'endettement non calculable : critère écarté du score.")
    else:
        f = _fraction_endettement(float(dti), p)
        situation = ("sous le seuil acceptable" if dti < p.dti_acceptable
                     else "zone de risque" if dti <= p.dti_max else "au-dessus du maximum")
        ajouter("endettement", f, _pct(float(dti)), f"{_pct(float(dti))} : {situation} ({p.dti_acceptable:.0f} % et {p.dti_max:.0f} %).")

    # 2. Contrat
    contrat = (metrics.get("contractType") or "").upper()
    f = _fraction_contrat(contrat, p) if contrat else None
    ajouter("contrat", f, contrat or "inconnu",
            "Type de contrat non identifié : critère écarté du score." if f is None
            else f"Contrat {contrat}.")

    # 3. Ancienneté
    if anciennete_mois is None:
        ajouter("anciennete", None, "inconnue", "Date d'embauche inconnue : critère écarté du score.")
    else:
        ans, mois = divmod(int(anciennete_mois), 12)
        ajouter("anciennete", _fraction_anciennete(int(anciennete_mois), p), f"{ans} an(s) {mois} mois",
                f"{ans} an(s) et {mois} mois dans l'emploi.")

    # 4. Incidents de paiement
    incidents = metrics.get("paymentIncidents")
    if incidents is None:
        ajouter("incidents", None, "inconnus", "Incidents non vérifiés (centrale des risques) : critère écarté du score.")
    else:
        n = int(incidents)
        ajouter("incidents", _fraction_incidents(n, p), str(n),
                "Aucun incident relevé." if n == 0 else f"{n} incident(s) de paiement relevé(s).")

    # 5. Montant par rapport au salaire
    revenu, montant = metrics.get("monthlyIncome"), metrics.get("requestedAmount")
    if not revenu or not montant:
        ajouter("montant", None, "inconnu", "Revenu ou montant manquant : critère écarté du score.")
    else:
        rapport = float(montant) / float(revenu)
        rapport_txt = f"{rapport:.1f}".replace(".", ",")
        ajouter("montant", _fraction_montant(rapport, p), f"{rapport_txt} × salaire",
                f"Le montant représente {rapport_txt} fois le salaire (maximum {p.multiple_salaire:g}).")

    connus = [c for c in criteres if c["connu"]]
    maximum_connu = sum(c["maximum"] for c in connus)
    points = sum(c["points"] for c in connus)
    provisoire = len(connus) < len(criteres)
    if maximum_connu == 0:
        total = 0
    elif provisoire:
        total = int(round(100 * points / maximum_connu))      # rapporté aux seuls critères connus
    else:
        total = points

    return {
        "methode": METHODE,
        "total": max(0, min(100, int(total))),
        "provisoire": provisoire,
        "pointsObtenus": points,
        "pointsConnus": maximum_connu,
        "prototype": not p.valide,
        "criteres": criteres,
    }
