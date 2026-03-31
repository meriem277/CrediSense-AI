"""
ocr_doctr.py — OCR via DocTR pour les documents structurés (formulaires, tableaux).

Quand l'utiliser :
- PDF scannés qui sont des formulaires bancaires
- Documents avec tableaux (bulletins de salaire, relevés structurés)
- Documents où la structure (colonnes, cases) est importante

DocTR utilise une architecture en deux étapes :
1. Détection : localise les mots dans le document (modèle DBNet)
2. Reconnaissance : lit les caractères de chaque mot détecté (modèle CRNN)

DocTR préserve mieux la structure spatiale du document que PaddleOCR,
ce qui sera utile pour le chunking par section dans la Couche 2.
"""

from pathlib import Path
import numpy as np
from PIL import Image
from doctr.io import DocumentFile
from doctr.models import ocr_predictor
from .utils import logger, validate_file_path, clean_text, Timer


# ── Initialisation du modèle DocTR ──────────────────────────────────────────
# Même principe que PaddleOCR : on charge le modèle une seule fois.

_doctr_model = None

def _get_doctr_model():
    """Retourne le modèle DocTR (charge si nécessaire)."""
    global _doctr_model
    if _doctr_model is None:
        logger.info("Chargement du modèle DocTR...")
        # pretrained=True : télécharge les poids pré-entraînés de Mindee
        _doctr_model = ocr_predictor(pretrained=True)
        logger.success("Modèle DocTR chargé.")
    return _doctr_model


class DocTROCRExtractor:
    """
    Extrait le texte d'une image ou d'un PDF via DocTR.
    
    Usage :
        extractor = DocTROCRExtractor()
        result = extractor.extract("formulaire_credit.pdf")
        print(result["text"])
    """
    
    def extract(self, file_path: str | Path) -> dict:
        """
        Lance DocTR sur un fichier et retourne le texte structuré.
        
        DocTR peut traiter directement les PDF multi-pages
        sans qu'on ait besoin de les convertir en images d'abord.
        
        Retourne un dictionnaire avec :
        - text        : texte complet nettoyé
        - pages       : texte organisé par page et par bloc
        - confidence  : confiance moyenne
        - source      : fichier source
        """
        path = validate_file_path(file_path)
        model = _get_doctr_model()
        
        logger.info(f"DocTR OCR sur : {path.name}")
        
        with Timer("DocTR") as t:
            # DocTR peut ouvrir PDF ou image directement
            extension = path.suffix.lower()
            if extension == ".pdf":
                doc = DocumentFile.from_pdf(str(path))
            else:
                doc = DocumentFile.from_images(str(path))
            
            # Prédiction : détection + reconnaissance
            result_doctr = model(doc)
        
        # ── Parser la sortie DocTR ───────────────────────────────────────
        # DocTR retourne un objet Document hiérarchique :
        # Document → Pages → Blocks → Lines → Words
        # Chaque Word a un contenu texte et une confiance.
        
        pages_data = []
        all_words = []
        all_confidences = []
        
        for page_idx, page in enumerate(result_doctr.pages):
            page_words = []
            page_lines = []
            
            for block in page.blocks:
                block_text_parts = []
                
                for line in block.lines:
                    line_words = []
                    
                    for word in line.words:
                        line_words.append(word.value)
                        all_confidences.append(word.confidence)
                        all_words.append(word.value)
                    
                    line_text = " ".join(line_words)
                    block_text_parts.append(line_text)
                    page_lines.append(line_text)
                
                block_text = "\n".join(block_text_parts)
                page_words.append(block_text)
            
            page_text = "\n\n".join(page_words)
            pages_data.append({
                "page": page_idx + 1,
                "text": clean_text(page_text),
            })
        
        full_text = "\n\n--- PAGE SUIVANTE ---\n\n".join(
            p["text"] for p in pages_data
        )
        avg_confidence = (
            sum(all_confidences) / len(all_confidences) if all_confidences else 0.0
        )
        
        result = {
            "text": clean_text(full_text),
            "pages": pages_data,
            "confidence": round(avg_confidence, 3),
            "num_words": len(all_words),
            "source": str(path),
            "extraction_method": "doctr",
            "duration_seconds": t.elapsed,
        }
        
        logger.success(
            f"DocTR terminé : {len(pages_data)} page(s), "
            f"{len(all_words)} mots, confiance {avg_confidence:.1%}, {t.elapsed:.2f}s"
        )
        
        return result