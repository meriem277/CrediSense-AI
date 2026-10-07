# services/dossier_texte.py
"""
Préparation du texte d'un dossier avant l'analyse crédit par le LLM.

Problème corrigé : le texte de TOUS les documents du dossier était coupé net à
4000 caractères. Avec plusieurs documents, seuls les premiers étaient lus : un
relevé bancaire (dettes, incidents) placé en fin de dossier disparaissait sans
aucun signalement, et le moteur de règles traitait alors les dettes inconnues
comme nulles.

Ici, le budget de caractères est réparti DOCUMENT PAR DOCUMENT :
  - les documents courts sont gardés en entier ;
  - les documents longs se partagent équitablement le reste du budget ;
  - un document raccourci garde son DÉBUT et sa FIN (les soldes et totaux d'un
    relevé sont souvent en fin de document) ;
  - aucun document n'est supprimé entièrement ;
  - chaque raccourcissement est renvoyé sous forme d'avertissement lisible,
    que l'agent voit à l'écran.

Les documents sont repérés par les en-têtes "=== TYPE (nom du fichier) ===" que
le backend ajoute devant chaque document. Sans en-têtes, le texte est traité
comme un seul bloc.
"""

import re
from typing import Optional

# Part du budget d'un document raccourci conservée au début (le reste : la fin)
PART_DEBUT = 0.7

MARQUEUR_OMISSION = "\n[… passage omis pour respecter la taille maximale …]\n"

# Un document en dessous de cette taille n'est jamais raccourci en dessous d'elle
# (sauf si le budget total ne le permet pas du tout)
TAILLE_MIN_SECTION = 600

_RE_ENTETE = re.compile(r"(?m)^=== (.+?) ===[ \t]*$")


def _decouper(texte: str) -> list[tuple[Optional[str], str]]:
    """
    Coupe le texte en sections (titre, texte complet de la section, en-tête inclus).
    Le texte éventuel avant le premier en-tête forme une section sans titre.
    """
    positions = [(m.start(), m.group(1)) for m in _RE_ENTETE.finditer(texte)]
    if not positions:
        return [(None, texte)]

    sections: list[tuple[Optional[str], str]] = []
    if positions[0][0] > 0 and texte[:positions[0][0]].strip():
        sections.append((None, texte[:positions[0][0]]))

    for i, (debut, titre) in enumerate(positions):
        fin = positions[i + 1][0] if i + 1 < len(positions) else len(texte)
        sections.append((titre, texte[debut:fin]))
    return sections


def decouper_sections(texte: str) -> list[tuple[Optional[str], str]]:
    """Version publique : [(titre de l'en-tête ou None, texte de la section)]."""
    return _decouper(texte or "")


def _repartir(tailles: list[int], budget: int) -> list[int]:
    """
    Partage `budget` entre des sections de tailles données : une section plus
    courte que la part équitable garde sa taille, le reste est redistribué aux
    sections plus longues (« water-filling »). Chaque section reçoit au moins
    TAILLE_MIN_SECTION si le budget le permet.
    """
    n = len(tailles)
    allocations = [0] * n
    restant = budget
    a_traiter = sorted(range(n), key=lambda i: tailles[i])

    for rang, i in enumerate(a_traiter):
        part = restant // (n - rang)
        allocations[i] = min(tailles[i], max(part, min(tailles[i], TAILLE_MIN_SECTION)))
        restant -= allocations[i]
    return allocations


def _raccourcir(section: str, budget: int) -> str:
    """Garde le début et la fin d'une section pour tenir dans `budget` caractères."""
    if len(section) <= budget:
        return section
    utile = max(budget - len(MARQUEUR_OMISSION), 0)
    debut = int(utile * PART_DEBUT)
    fin = utile - debut
    fin_texte = section[len(section) - fin:] if fin > 0 else ""
    return section[:debut].rstrip() + MARQUEUR_OMISSION + fin_texte.lstrip()


def preparer_texte_dossier(texte: str, max_chars: int) -> tuple[str, list[str]]:
    """
    Renvoie (texte prêt pour le LLM, avertissements).
    Le texte renvoyé ne dépasse pas `max_chars` caractères (à quelques caractères
    près des marqueurs d'omission). `avertissements` est vide si rien n'a été retiré.
    """
    texte = texte or ""
    if max_chars <= 0 or len(texte) <= max_chars:
        return texte, []

    sections = _decouper(texte)
    # Les séparateurs entre sections comptent aussi dans le budget
    budget = max_chars
    allocations = _repartir([len(s) for _, s in sections], budget)

    morceaux: list[str] = []
    avertissements: list[str] = []
    for (titre, section), alloc in zip(sections, allocations):
        if len(section) > alloc:
            morceaux.append(_raccourcir(section, alloc))
            if titre:
                avertissements.append(
                    f"Le document « {titre} » a été raccourci ({len(section)} → "
                    f"{alloc} caractères) : des informations ont pu ne pas être analysées."
                )
        else:
            morceaux.append(section)

    if not avertissements:
        # Texte sans en-têtes (une seule section), ou section sans titre raccourcie
        avertissements.append(
            f"Le dossier a été raccourci ({len(texte)} → {max_chars} caractères) : "
            f"des informations ont pu ne pas être analysées."
        )

    return "".join(morceaux), avertissements
