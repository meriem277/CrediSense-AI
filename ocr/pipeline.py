"""
pipeline.py — Chef d'orchestre de la Couche 1.

C'est le SEUL fichier que la Couche 2 (RAG Pipeline) va appeler.
Il cache toute la complexité : quel OCR utiliser, comment parser, etc.

Flux d'exécution :
    fichier entrant
        → FileDetector.detect()
            → PDF_NATIVE  → PDFExtractor.extract()
            → IMAGE       → PaddleOCRExtractor.extract()
            → PDF_SCANNED → DocTROCRExtractor.extract()
        → retourne dict standardisé avec "text", "metadata", etc.
"""

from pathlib import Path
from .detector import FileDetector, FileType
from .pdf_extractor import PDFExtractor
from .ocr_paddle import PaddleOCRExtractor
from .ocr_doctr import DocTROCRExtractor
from .utils import logger, validate_file_path, Timer


class OCRPipeline:
    """
    Point d'entrée unique de la Couche 1.
    
    Usage depuis la Couche 2 :
        pipeline = OCRPipeline()
        result = pipeline.process("dossier_credit.pdf")
        texte_brut = result["text"]
    
    Le résultat retourné est TOUJOURS dans le même format,
    peu importe le type de fichier en entrée.
    """
    
    def __init__(self):
        self.detector = FileDetector()
        self.pdf_extractor = PDFExtractor()
        self.paddle_extractor = PaddleOCRExtractor()
        self.doctr_extractor = DocTROCRExtractor()
        logger.info("OCRPipeline initialisé.")
    
    def process(self, file_path: str | Path) -> dict:
        """
        Traite un fichier et retourne son texte extrait.
        
        Paramètre : file_path — chemin vers le fichier à traiter
        
        Retourne toujours un dict avec au minimum :
        - "text"               : str — texte complet extrait
        - "extraction_method"  : str — quelle méthode a été utilisée
        - "file_type"          : str — type détecté
        - "source"             : str — chemin du fichier
        - "success"            : bool — True si extraction réussie
        - "error"              : str | None — message d'erreur si échec
        """
        path = validate_file_path(file_path)
        
        logger.info(f"═══ Début traitement Couche 1 : {path.name} ═══")
        
        with Timer(f"Pipeline complet ({path.name})") as total_timer:
            
            # ── Étape 1 : Détection du type de fichier ──────────────────
            file_type = self.detector.detect(path)
            
            if file_type == FileType.UNSUPPORTED:
                return {
                    "text": "",
                    "file_type": "unsupported",
                    "extraction_method": None,
                    "source": str(path),
                    "success": False,
                    "error": f"Format de fichier non supporté : {path.suffix}",
                }
            
            # ── Étape 2 : Extraction selon le type ──────────────────────
            try:
                if file_type == FileType.PDF_NATIVE:
                    result = self.pdf_extractor.extract(path)
                
                elif file_type == FileType.IMAGE:
                    result = self.paddle_extractor.extract(path)
                
                elif file_type == FileType.PDF_SCANNED:
                    result = self.doctr_extractor.extract(path)
                
                # Ajouter les infos communes
                result["file_type"] = file_type.value
                result["success"] = True
                result["error"] = None
                result["total_duration_seconds"] = total_timer.elapsed
                
            except Exception as e:
                logger.error(f"Erreur extraction : {e}")
                return {
                    "text": "",
                    "file_type": file_type.value,
                    "extraction_method": "failed",
                    "source": str(path),
                    "success": False,
                    "error": str(e),
                }
        
        logger.info(
            f"═══ Couche 1 terminée : {len(result['text'])} caractères extraits ═══"
        )
        
        return result