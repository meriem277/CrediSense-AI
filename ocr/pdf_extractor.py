# ocr/pdf_extractor.py
"""
Extraction directe de texte depuis un PDF natif via PyMuPDF.
Aucun OCR impliqué — rapide et précis sur les PDF générés par ordinateur
(relevés bancaires exportés, fiches de paie numériques).
"""

import fitz  # PyMuPDF
from pathlib import Path


def extract_native_pdf(path: str) -> tuple[str, bool]:
    """
    Tente d'extraire le texte natif d'un PDF.

    Retourne :
        (texte, is_native) où is_native=False signifie que le PDF
        est scanné et qu'il faut passer à un moteur OCR.
    """
    doc = fitz.open(path)
    pages_text = []
    total_chars = 0

    for page_num in range(len(doc)):
        page = doc[page_num]

        # Extraction du texte avec préservation des blocs
        # "blocks" conserve la structure : colonnes, tableaux, headers
        blocks = page.get_text("blocks", sort=True)

        page_text = ""
        for block in blocks:
            # block = (x0, y0, x1, y1, text, block_no, block_type)
            if block[6] == 0:  # type 0 = bloc texte (pas image)
                page_text += block[4].strip() + "\n"

        pages_text.append(page_text)
        total_chars += len(page_text)

    doc.close()

    full_text = "\n\n--- PAGE ---\n\n".join(pages_text)

    # Heuristique : si moins de 80 chars en moyenne par page → PDF scanné
    avg_chars_per_page = total_chars / max(1, len(pages_text))
    is_native = avg_chars_per_page >= 80

    return full_text, is_native


def pdf_to_images(path: str, dpi: int = 200) -> list:
    """
    Convertit chaque page du PDF en image PIL.
    Utilisé quand le PDF est scanné → on passe les images à l'OCR.
    dpi=200 est le bon compromis vitesse/qualité pour l'OCR.
    """
    from PIL import Image
    import io

    doc = fitz.open(path)
    images = []

    for page in doc:
        # mat = matrice de transformation pour le DPI voulu
        mat = fitz.Matrix(dpi / 72, dpi / 72)
        pix = page.get_pixmap(matrix=mat, colorspace=fitz.csRGB)
        img_bytes = pix.tobytes("png")
        images.append(Image.open(io.BytesIO(img_bytes)))

    doc.close()
    return images