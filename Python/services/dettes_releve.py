# services/dettes_releve.py
"""
Détection, PAR RÈGLES, des échéances de crédit dans un relevé bancaire.

Pourquoi : jusqu'ici les dettes existantes venaient uniquement du LLM, et une dette
non lue valait « 0 » dans le calcul du taux d'endettement — un dossier avec un prêt en
cours pouvait ressortir meilleur qu'il n'est. Avec ce module :
  - si le relevé montre une échéance de prêt, on la retrouve de façon reproductible ;
  - si le relevé est présent et n'en montre aucune, on peut affirmer « pas de dette
    détectée » (au lieu de « dette inconnue ») ;
  - s'il n'y a pas de relevé du tout, les dettes restent INCONNUES.

Limite assumée : c'est une heuristique sur du texte (OCR ou PDF). Elle repère les lignes
du type « PRELEVEMENT ECHEANCE PRET CONSOMMATION … 250,000 » ; les colonnes débit/crédit
ne sont pas distinguables dans le texte, on prend le premier montant de la ligne. Le
résultat est donc toujours présenté comme « détecté », à confirmer.
"""

import re
import unicodedata
from typing import Optional

from services.dossier_texte import decouper_sections

# Un mot « crédit » ET un mot « échéance/prélèvement » sur la même ligne
_RE_CREDIT   = re.compile(r"\b(PRET|CREDIT|LEASING|CONSOMMATION|IMMOBILIER)\b")
_RE_ECHEANCE = re.compile(r"\b(ECHEANCE|ECH|PRELEVEMENT|PRLV|REMBOURSEMENT|REMB|MENSUALITE)\b")
# Lignes qui contiennent ces mots mais ne sont pas une dette (en-têtes, totaux, salaire)
_RE_EXCLUS   = re.compile(r"\b(SALAIRE|TOTAL|SOLDE|TOTAUX)\b")

# Montant tunisien : « 250,000 », « 4 135,550 » (millimes obligatoires)
_RE_MONTANT = re.compile(r"(?<![\d,.])(?:\d{1,3}(?:[  ]\d{3})*|\d+),\d{3}(?!\d)")
_RE_DATE    = re.compile(r"(\d{2})[/.\-](\d{2})[/.\-](\d{4})")

_RE_RELEVE = re.compile(r"RELEVE DE COMPTE|EXTRAIT DE COMPTE|RELEVE BANCAIRE")


def _normaliser(texte: str) -> str:
    """Majuscules sans accents : « Échéance prêt » -> « ECHEANCE PRET »."""
    decompose = unicodedata.normalize("NFKD", texte)
    return "".join(c for c in decompose if not unicodedata.combining(c)).upper()


def _montant(valeur: str) -> float:
    return float(valeur.replace(" ", "").replace(" ", "").replace(",", "."))


def _sections_releve(texte_dossier: str) -> list[str]:
    """Les parties du dossier qui sont des relevés bancaires (par titre, sinon par contenu)."""
    releves = []
    for titre, section in decouper_sections(texte_dossier):
        debut = _normaliser(section[:800])
        if (titre and "RELEVE" in _normaliser(titre)) or _RE_RELEVE.search(debut):
            releves.append(section)
    return releves


def _ligne_est_echeance(ligne_normalisee: str) -> bool:
    return (bool(_RE_CREDIT.search(ligne_normalisee))
            and bool(_RE_ECHEANCE.search(ligne_normalisee))
            and not _RE_EXCLUS.search(ligne_normalisee))


_RE_DEBUT_OPERATION = re.compile(r"^\s*\d{2}[/.\-]\d{2}[/.\-]\d{4}")
# Fin du tableau : ces lignes ferment l'opération en cours (elles n'en font pas partie)
_RE_FIN_TABLEAU = re.compile(r"^\s*(TOTAL|TOTAUX|NOUVEAU SOLDE|SOLDE FINAL|ANCIEN SOLDE)")


def _operations(section: str) -> list[str]:
    """
    Découpe un relevé en OPÉRATIONS : une opération commence à une ligne qui débute par
    une date et regroupe les lignes suivantes jusqu'à la prochaine date.
    Nécessaire car un PDF natif donne une cellule par ligne (date / libellé / montants),
    alors que l'OCR regroupe déjà une opération sur une seule ligne : les deux marchent.
    """
    operations: list[list[str]] = []
    courante: Optional[list[str]] = None

    for ligne in section.splitlines():
        if _RE_DEBUT_OPERATION.match(ligne):
            courante = [ligne.strip()]
            operations.append(courante)
        elif _RE_FIN_TABLEAU.match(_normaliser(ligne)):
            courante = None
        elif courante is not None and ligne.strip():
            courante.append(ligne.strip())

    return [" ".join(morceaux) for morceaux in operations]


def detecter_dettes_releve(texte_dossier: str) -> dict:
    """
    Renvoie :
      releve_present : au moins un relevé bancaire dans le dossier
      exploitable    : le relevé a pu être lu (au moins une opération datée) ; sinon on ne
                       peut rien conclure, ni dette ni absence de dette
      mensualite     : somme mensuelle des échéances de crédit détectées (None si aucune)
      details        : les lignes retenues (pour l'audit)
      approximatif   : True si les dates n'ont pas pu être lues (estimation moins sûre)
    La mensualité retenue est la plus élevée des DEUX DERNIERS mois du relevé : un prêt
    soldé depuis longtemps n'est pas compté, un prélèvement du début de mois non plus
    s'il manque dans le dernier mois partiel.
    """
    releves = _sections_releve(texte_dossier or "")
    if not releves:
        return {"releve_present": False, "exploitable": False, "mensualite": None,
                "details": [], "approximatif": False}

    mois_du_releve: set[str] = set()        # TOUS les mois couverts par le relevé
    par_mois: dict[str, float] = {}         # échéances de crédit détectées, par mois
    details: list[str] = []

    for section in releves:
        for operation in _operations(section):
            date = _RE_DATE.search(operation)
            cle = f"{date.group(3)}-{date.group(2)}" if date else None
            if cle:
                mois_du_releve.add(cle)

            if not _ligne_est_echeance(_normaliser(operation)):
                continue
            montants = _RE_MONTANT.findall(operation)
            if not montants:
                continue
            details.append(operation)
            if cle:
                par_mois[cle] = par_mois.get(cle, 0.0) + _montant(montants[0])   # débit : 1er montant

    # Les opérations sont repérées par leur date : sans date lisible, on ne devine pas
    # (le relevé reste « présent » : l'appelant saura qu'aucune échéance n'a pu être lue).
    # On ne regarde que les DEUX DERNIERS MOIS DU RELEVÉ : un prêt soldé depuis longtemps
    # n'est pas compté, et on ne dépend pas d'un mois partiel.
    derniers = sorted(mois_du_releve)[-2:]
    mensualite = max((par_mois.get(m, 0.0) for m in derniers), default=0.0)
    return {
        "releve_present": True,
        # Au moins une opération datée a été lue : on peut affirmer « aucune échéance trouvée ».
        # Sinon (OCR trop mauvais, mise en page inhabituelle) le relevé n'a pas pu être analysé
        # et l'absence de dette NE PEUT PAS être conclue.
        "exploitable":    bool(mois_du_releve),
        "mensualite":     round(mensualite, 3) if mensualite > 0 else None,
        "details":        details if mensualite > 0 else [],
        "approximatif":   False,
    }
