# rag/cleaner.py
"""
Nettoyage et normalisation du texte brut extrait par OCR.

Problèmes courants dans les documents bancaires :
  - Caractères parasites (|, ~, _, *, #) issus des tableaux OCR
  - Lignes vides consécutives
  - Espaces multiples entre mots
  - Numéros de page ("Page 1 / 3")
  - En-têtes/pieds de page répétitifs
  - Encodage mixte arabe/français sur la même ligne
"""

import re
from dataclasses import dataclass


@dataclass
class CleanResult:
    text: str           # texte nettoyé
    nb_chars_avant: int
    nb_chars_apres: int
    taux_reduction: float


def clean_text(raw_text: str) -> CleanResult:
    """
    Pipeline de nettoyage complet.
    Applique les étapes dans l'ordre optimal.
    """
    avant = len(raw_text)
    text = raw_text

    # Étape 1 : caractères de contrôle (sauf \n et \t)
    text = _remove_control_chars(text)

    # Étape 2 : artefacts OCR fréquents dans les docs bancaires
    text = _remove_ocr_artifacts(text)

    # Étape 3 : numéros de page et en-têtes répétitifs
    text = _remove_page_noise(text)

    # Étape 4 : normalisation des espaces et des lignes
    text = _normalize_whitespace(text)

    # Étape 5 : normalisation des nombres (séparateurs décimaux)
    text = _normalize_numbers(text)

    apres = len(text)
    taux = round((1 - apres / max(1, avant)) * 100, 1)

    return CleanResult(
        text=text.strip(),
        nb_chars_avant=avant,
        nb_chars_apres=apres,
        taux_reduction=taux
    )


def _remove_control_chars(text: str) -> str:
    """Supprime les caractères de contrôle invisibles."""
    # Garde \n (0x0A) et \t (0x09), supprime tout le reste < 0x20
    return re.sub(r'[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]', '', text)


def _remove_ocr_artifacts(text: str) -> str:
    """
    Supprime les artefacts typiques de l'OCR sur documents bancaires :
    lignes de tirets, barres verticales isolées, astérisques décoratifs.
    """
    # Lignes composées uniquement de symboles répétés (--- === ___ ...)
    text = re.sub(r'^[\s\-=_~*|#.]{3,}$', '', text, flags=re.MULTILINE)

    # Barres verticales isolées (artefacts de tableau OCR)
    text = re.sub(r'(?<!\w)\|(?!\w)', ' ', text)

    # Séquences de points répétés (........)
    text = re.sub(r'\.{4,}', ' ', text)

    # Caractères spéciaux parasites fréquents
    text = re.sub(r'[†‡§¶©®™°]', '', text)

    return text


def _remove_page_noise(text: str) -> str:
    """
    Supprime les numéros de page, en-têtes et pieds de page répétitifs.
    Patterns courants dans les relevés bancaires tunisiens.
    """
    patterns = [
        r'page\s*\d+\s*[/sur]\s*\d+',          # "Page 1 / 3" ou "Page 1 sur 3"
        r'^\s*\d+\s*$',                          # ligne avec seulement un numéro
        r'confidentiel\s*[-–]\s*usage\s*interne',
        r'imprim[eé]\s*le\s*\d{2}/\d{2}/\d{4}', # "Imprimé le 01/01/2024"
        r'ce\s*document\s*est\s*strictement\s*confidentiel',
    ]
    for pattern in patterns:
        text = re.sub(pattern, '', text, flags=re.IGNORECASE | re.MULTILINE)
    return text


def _normalize_whitespace(text: str) -> str:
    """
    Normalise les espaces et sauts de ligne.
    Conserve la structure des paragraphes (double \n).
    """
    # Espaces multiples → un seul espace (sur une même ligne)
    text = re.sub(r'[ \t]{2,}', ' ', text)

    # Trim chaque ligne
    lines = [line.strip() for line in text.split('\n')]

    # Supprime les lignes vides en excès (max 2 consécutives)
    result_lines = []
    empty_count = 0
    for line in lines:
        if line == '':
            empty_count += 1
            if empty_count <= 2:
                result_lines.append(line)
        else:
            empty_count = 0
            result_lines.append(line)

    return '\n'.join(result_lines)


def _normalize_numbers(text: str) -> str:
    """
    Normalise les formats numériques bancaires tunisiens.
    Exemples :
      "3 200,50 DT"  → conservé tel quel (format standard TN)
      "3.200,50"     → conservé
      "3200.50"      → conservé
    But principal : supprimer les espaces parasites dans les nombres
    introduits par l'OCR : "3 2 0 0" → "3200"
    """
    # Corrige les espaces OCR dans les séquences de chiffres
    # "1 2 3 4" uniquement si tous séparés par 1 espace → "1234"
    text = re.sub(
        r'(?<!\w)(\d)\s(\d)\s(\d)\s(\d)(?!\w)',
        r'\1\2\3\4',
        text
    )
    return text