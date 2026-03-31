"""
pdf_extractor.py — Extrait le texte d'un PDF natif (non scanné) sans OCR.

Utilisé uniquement quand detector.py retourne FileType.PDF_NATIVE.

Avantages :
- Très rapide (millisecondes vs secondes pour l'OCR)
- Précision parfaite (pas de risque d'erreur de reconnaissance)
- Préserve la structure (sauts de page, paragraphes)

PyMuPDF (fitz) est utilisé car il est le plus rapide et le plus complet
pour extraire le texte structuré d'un PDF.
"""

from pathlib import Path
import fitz  # PyMuPDF
from .utils import logger, validate_file_path, clean_text, Timer


class PDFExtractor:
    """
    Extrait le texte d'un PDF numérique (non scanné).
    
    Usage :
        extractor = PDFExtractor()
        result = extractor.extract("releve_bancaire.pdf")
        print(result["text"])
        print(result["num_pages"])
    """
    
    def extract(self, file_path: str | Path) -> dict:
        """
        Extrait tout le texte du PDF.
        
        Retourne un dictionnaire avec :
        - text        : texte complet nettoyé
        - num_pages   : nombre de pages
        - pages_text  : liste du texte par page (utile pour le chunking ensuite)
        - metadata    : infos du PDF (auteur, date de création...)
        - source      : chemin du fichier source
        """
        path = validate_file_path(file_path)
        
        with Timer("PDF extraction") as t:
            doc = fitz.open(str(path))
            
            pages_text = []
            full_text_parts = []
            
            for page_num in range(len(doc)):
                page = doc[page_num]
                
                # get_text("text") : extraction simple du texte
                # get_text("blocks") : extraction par blocs (paragraphes)
                # On utilise "text" pour avoir le flux de texte linéaire
                raw_text = page.get_text("text")
                cleaned = clean_text(raw_text)
                
                pages_text.append({
                    "page": page_num + 1,
                    "text": cleaned,
                    "char_count": len(cleaned),
                })
                full_text_parts.append(cleaned)
            
            # Métadonnées du PDF
            metadata = doc.metadata
            doc.close()
        
        full_text = "\n\n--- PAGE SUIVANTE ---\n\n".join(full_text_parts)
        
        result = {
            "text": full_text,
            "num_pages": len(pages_text),
            "pages_text": pages_text,
            "metadata": metadata,
            "source": str(path),
            "extraction_method": "pdf_native",
            "duration_seconds": t.elapsed,
        }
        
        logger.success(
            f"PDF extrait : {len(doc) if False else result['num_pages']} pages, "
            f"{len(full_text)} caractères, {t.elapsed:.2f}s"
        )
        
        return result