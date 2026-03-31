"""
utils.py — Fonctions utilitaires partagées par tous les modules de la couche 1.

Contenu :
- Configuration du système de logs (loguru)
- Nettoyage du texte OCR (supprimer caractères parasites)
- Validation des chemins de fichiers
- Timer de performance
"""

import re
import time
from pathlib import Path
from loguru import logger
import sys


# ── Configuration des logs ──────────────────────────────────────────────────
# On supprime le handler par défaut de loguru et on en crée un personnalisé.
# Format : heure | niveau | fichier:ligne | message
logger.remove()
logger.add(
    sys.stderr,
    format="<green>{time:HH:mm:ss}</green> | <level>{level: <8}</level> | <cyan>{name}</cyan>:<cyan>{line}</cyan> — <level>{message}</level>",
    level="DEBUG",
)
logger.add(
    "logs/couche1.log",          # Fichier de log persistant
    rotation="10 MB",            # Nouveau fichier après 10 Mo
    retention="7 days",          # Garde les logs 7 jours
    level="INFO",
)


def clean_text(raw_text: str) -> str:
    """
    Nettoie le texte brut sorti de l'OCR.
    
    Problèmes courants de l'OCR :
    - Lignes vides multiples
    - Espaces en début/fin de ligne
    - Caractères de contrôle invisibles (\x00, \x0c...)
    - Tirets de coupure de mot en fin de ligne
    
    Retourne : texte nettoyé
    """
    if not raw_text:
        return ""
    
    # Supprimer les caractères de contrôle (sauf \n et \t)
    text = re.sub(r'[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]', '', raw_text)
    
    # Joindre les mots coupés par un tiret en fin de ligne
    # Exemple : "rem-\nbourser" → "rembourser"
    text = re.sub(r'-\n(\w)', r'\1', text)
    
    # Réduire les espaces multiples en un seul
    text = re.sub(r'[ \t]+', ' ', text)
    
    # Réduire les lignes vides multiples (plus de 2) en 2 max
    text = re.sub(r'\n{3,}', '\n\n', text)
    
    # Supprimer les espaces en début et fin de chaque ligne
    lines = [line.strip() for line in text.split('\n')]
    
    return '\n'.join(lines).strip()


def validate_file_path(file_path: str | Path) -> Path:
    """
    Vérifie que le fichier existe et est lisible.
    Lève une exception claire si ce n'est pas le cas.
    
    Retourne : un objet Path si tout va bien
    """
    path = Path(file_path)
    
    if not path.exists():
        raise FileNotFoundError(f"Fichier introuvable : {path}")
    
    if not path.is_file():
        raise ValueError(f"Ce chemin n'est pas un fichier : {path}")
    
    if path.stat().st_size == 0:
        raise ValueError(f"Le fichier est vide : {path}")
    
    return path


class Timer:
    """
    Mesure le temps d'exécution d'un bloc de code.
    
    Usage :
        with Timer("PaddleOCR") as t:
            result = ocr.run(image)
        print(f"Durée : {t.elapsed:.2f}s")
    """
    
    def __init__(self, name: str = ""):
        self.name = name
        self.elapsed = 0.0
    
    def __enter__(self):
        self.start = time.perf_counter()
        return self
    
    def __exit__(self, *args):
        self.elapsed = time.perf_counter() - self.start
        if self.name:
            logger.debug(f"⏱  {self.name} : {self.elapsed:.3f}s")