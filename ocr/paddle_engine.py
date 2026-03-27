# ocr/paddle_engine.py
"""
Moteur PaddleOCR.

Avantages pour le bancaire tunisien :
  - Supporte arabe (AR) + français (FR) simultanément
  - Excellent sur les tableaux et les tampons
  - Détecte et corrige l'orientation des textes inclinés (use_angle_cls=True)

Inconvénient :
  - Premier lancement lent (~30s : téléchargement des modèles ~200 Mo)
  - Après : mis en cache, très rapide
"""

from pathlib import Path
import numpy as np

# Singleton : on ne charge le modèle qu'une seule fois en mémoire
_paddle_instance = None


def _get_paddle(lang: str = "fr"):
    """
    
    Retourne l'instance PaddleOCR (chargée une seule fois).
    lang : "fr" pour français, "ar" pour arabe, "en" pour anglais.
    Pour un document mixte FR+AR, on fait deux passes.
    """
    global _paddle_instance
    if _paddle_instance is None:
        from paddleocr import PaddleOCR
        _paddle_instance = PaddleOCR(
            use_angle_cls=True,   # correction inclinaison
            lang=lang,
            show_log=False,       # silence les logs verbeux
            use_gpu=_has_gpu(),   # auto-détection GPU
        )
    return _paddle_instance


def _has_gpu() -> bool:
    """Vérifie si un GPU CUDA est disponible."""
    try:
        import paddle
        return paddle.device.cuda.device_count() > 0
    except Exception:
        return False


def extract_with_paddle(image_path: str) -> str:
    """
    Extrait le texte d'une image via PaddleOCR.

    Args:
        image_path : chemin vers PNG, JPG, TIFF ou page PDF convertie

    Returns:
        Texte extrait, lignes séparées par \n
    """
    ocr = _get_paddle()
    result = ocr.ocr(image_path, cls=True)

    if not result or result[0] is None:
        return ""

    lines = []
    confidences = []

    for page_result in result:
        if not page_result:
            continue
        for line in page_result:
            # line = [[coords_bbox], [texte, confiance]]
            text = line[1][0]
            confidence = line[1][1]

            # On filtre les détections trop incertaines (< 50%)
            if confidence >= 0.5:
                lines.append(text)
                confidences.append(confidence)

    # Statistique utile pour le debug
    if confidences:
        avg_conf = sum(confidences) / len(confidences)
        print(f"    PaddleOCR : {len(lines)} lignes, "
              f"confiance moyenne : {avg_conf:.1%}")

    return "\n".join(lines)


def extract_with_paddle_from_pil(pil_image) -> str:
    """
    Variante acceptant une image PIL directement (pour PDF scanné).
    Sauvegarde temporairement l'image puis appelle extract_with_paddle.
    """
    import tempfile, os
    with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as tmp:
        pil_image.save(tmp.name)
        tmp_path = tmp.name

    try:
        return extract_with_paddle(tmp_path)
    finally:
        os.unlink(tmp_path)