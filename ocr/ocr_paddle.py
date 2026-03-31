"""
ocr_paddle.py — OCR via PaddleOCR pour les images denses et les scans complexes.

Quand l'utiliser :
- Photos de documents (photo avec smartphone)
- Images JPEG/PNG de relevés bancaires, bulletins de salaire
- Documents avec texte dense, peu structuré

PaddleOCR détecte d'abord les zones de texte dans l'image (détection),
puis reconnaît les caractères dans chaque zone (reconnaissance).

Modèles utilisés (téléchargés automatiquement au premier lancement) :
- Détection : DB (MobileNetV3) — repère les rectangles de texte
- Reconnaissance : CRNN — reconnaît les caractères
- Classification d'angle : corrige les textes tournés

Note : le premier lancement est lent (~30s) car il télécharge les modèles.
Les lancements suivants sont rapides car les modèles sont mis en cache.
"""

from pathlib import Path
import numpy as np
from PIL import Image
from paddleocr import PaddleOCR
from .utils import logger, validate_file_path, clean_text, Timer


# ── Initialisation du moteur PaddleOCR ──────────────────────────────────────
# On crée l'instance UNE SEULE FOIS (variable au niveau du module).
# Pourquoi ? Charger les modèles de deep learning prend 2-5 secondes.
# Si on recrée l'instance à chaque appel, l'application serait très lente.

_paddle_engine = None  # Sera initialisé au premier appel (lazy loading)

def _get_paddle_engine() -> PaddleOCR:
    """Retourne l'instance PaddleOCR (crée si nécessaire)."""
    global _paddle_engine
    if _paddle_engine is None:
        logger.info("Initialisation de PaddleOCR (premier chargement des modèles)...")
        _paddle_engine = PaddleOCR(
            use_angle_cls=True,   # Corrige les textes à 90° ou 180°
            lang="fr",            # Modèle optimisé pour le français
            use_gpu=False,        # Mettre True si tu as un GPU NVIDIA avec CUDA
            show_log=False,       # Désactive les logs verbeux de Paddle
        )
        logger.success("PaddleOCR initialisé.")
    return _paddle_engine


class PaddleOCRExtractor:
    """
    Extrait le texte d'une image via PaddleOCR.
    
    Usage :
        extractor = PaddleOCRExtractor()
        result = extractor.extract("scan_releve.jpg")
        print(result["text"])
    """
    
    def extract(self, file_path: str | Path) -> dict:
        """
        Lance PaddleOCR sur une image et retourne le texte reconnu.
        
        Retourne un dictionnaire avec :
        - text        : texte complet nettoyé
        - confidence  : score de confiance moyen (0 à 1)
        - blocks      : liste des blocs détectés avec position et confiance
        - source      : fichier source
        """
        path = validate_file_path(file_path)
        engine = _get_paddle_engine()
        
        logger.info(f"PaddleOCR sur : {path.name}")
        
        with Timer("PaddleOCR") as t:
            # PaddleOCR accepte un chemin (str) ou un array numpy
            results = engine.ocr(str(path), cls=True)
        
        # ── Parser les résultats de PaddleOCR ───────────────────────────
        # results est une liste de listes :
        # results[0] = liste des blocs détectés sur la page
        # Chaque bloc = [coordonnées_boîte, (texte, confiance)]
        # Exemple : [[[10,20],[100,20],[100,40],[10,40]], ("Salaire net", 0.97)]
        
        blocks = []
        text_lines = []
        confidences = []
        
        if results and results[0]:
            for line in results[0]:
                if line and len(line) >= 2:
                    bbox = line[0]          # Coordonnées du rectangle
                    text_conf = line[1]     # Tuple (texte, confiance)
                    
                    if text_conf:
                        text = text_conf[0]
                        conf = text_conf[1]
                        
                        blocks.append({
                            "text": text,
                            "confidence": round(conf, 3),
                            "bbox": bbox,
                        })
                        text_lines.append(text)
                        confidences.append(conf)
        
        raw_text = "\n".join(text_lines)
        avg_confidence = sum(confidences) / len(confidences) if confidences else 0.0
        
        result = {
            "text": clean_text(raw_text),
            "confidence": round(avg_confidence, 3),
            "blocks": blocks,
            "num_blocks": len(blocks),
            "source": str(path),
            "extraction_method": "paddleocr",
            "duration_seconds": t.elapsed,
        }
        
        logger.success(
            f"PaddleOCR terminé : {len(blocks)} blocs, "
            f"confiance moyenne {avg_confidence:.1%}, {t.elapsed:.2f}s"
        )
        
        return result