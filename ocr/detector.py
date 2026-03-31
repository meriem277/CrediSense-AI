"""
detector.py — Détecte le type de fichier entrant et choisit la stratégie d'extraction.

Logique de décision :
1. Est-ce un PDF ?
   → Oui : Le PDF contient-il du texte natif ?
      → Oui : FileType.PDF_NATIVE  (extraction directe, rapide)
      → Non : FileType.PDF_SCANNED (il faut OCR : DocTR)
2. Est-ce une image (JPG, PNG, TIFF, BMP) ?
   → FileType.IMAGE (OCR : PaddleOCR)
3. Autre → FileType.UNSUPPORTED

Pourquoi distinguer PDF_NATIVE et PDF_SCANNED ?
- Un PDF natif (créé depuis Word, LibreOffice...) contient les caractères en texte.
  On peut l'extraire directement sans OCR = 100x plus rapide et 100% précis.
- Un PDF scanné est juste une image dans une enveloppe PDF.
  Il faut faire tourner un modèle OCR dessus.
"""

from enum import Enum
from pathlib import Path
import fitz  # PyMuPDF — pour lire les PDF
from .utils import logger, validate_file_path


class FileType(Enum):
    """Énumération des types de fichiers supportés."""
    PDF_NATIVE  = "pdf_native"   # PDF avec texte extractible directement
    PDF_SCANNED = "pdf_scanned"  # PDF qui est en réalité un scan (image)
    IMAGE       = "image"        # Image directe (JPG, PNG, TIFF...)
    UNSUPPORTED = "unsupported"  # Format non géré


# Extensions d'images supportées
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".tiff", ".tif", ".bmp", ".webp"}

# Seuil : si le PDF a moins de N caractères par page en moyenne,
# on le considère comme scanné.
TEXT_CHARS_THRESHOLD = 50


class FileDetector:
    """
    Analyse un fichier et retourne son type (FileType).
    
    Usage :
        detector = FileDetector()
        file_type = detector.detect("dossier_client.pdf")
        # → FileType.PDF_NATIVE ou PDF_SCANNED selon le contenu
    """
    
    def detect(self, file_path: str | Path) -> FileType:
        """
        Point d'entrée principal. Détecte le type d'un fichier.
        
        Paramètre : file_path — chemin vers le fichier
        Retourne  : FileType
        """
        path = validate_file_path(file_path)
        extension = path.suffix.lower()
        
        logger.info(f"Détection du fichier : {path.name}")
        
        # ── Cas 1 : c'est une image ─────────────────────────────────────
        if extension in IMAGE_EXTENSIONS:
            logger.info(f"→ Type détecté : IMAGE")
            return FileType.IMAGE
        
        # ── Cas 2 : c'est un PDF ────────────────────────────────────────
        if extension == ".pdf":
            return self._analyze_pdf(path)
        
        # ── Cas 3 : format non supporté ─────────────────────────────────
        logger.warning(f"→ Format non supporté : {extension}")
        return FileType.UNSUPPORTED
    
    def _analyze_pdf(self, path: Path) -> FileType:
        """
        Analyse l'intérieur du PDF pour savoir s'il contient du texte natif.
        
        Méthode : on ouvre le PDF, on extrait le texte de la première page.
        Si on obtient suffisamment de caractères → PDF natif.
        Sinon → PDF scanné.
        """
        try:
            doc = fitz.open(str(path))
            total_chars = 0
            pages_checked = min(3, len(doc))  # On vérifie au max les 3 premières pages
            
            for page_num in range(pages_checked):
                page = doc[page_num]
                text = page.get_text()
                total_chars += len(text.strip())
            
            doc.close()
            
            avg_chars_per_page = total_chars / pages_checked if pages_checked > 0 else 0
            
            if avg_chars_per_page > TEXT_CHARS_THRESHOLD:
                logger.info(f"→ PDF NATIF (moyenne {avg_chars_per_page:.0f} chars/page)")
                return FileType.PDF_NATIVE
            else:
                logger.info(f"→ PDF SCANNÉ (moyenne {avg_chars_per_page:.0f} chars/page — probablement une image)")
                return FileType.PDF_SCANNED
        
        except Exception as e:
            logger.error(f"Erreur lors de l'analyse PDF : {e}")
            # En cas de doute, on tente l'OCR
            return FileType.PDF_SCANNED