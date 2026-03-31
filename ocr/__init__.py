# Ce fichier dit à Python que le dossier "couche1" est un module importable.
# Il expose l'interface publique de la couche 1.
# Grâce à ça, dans d'autres fichiers tu peux écrire :
#   from couche1 import OCRPipeline
# au lieu de :
#   from couche1.pipeline import OCRPipeline

from .pipeline import OCRPipeline
from .detector import FileDetector, FileType

__all__ = ["OCRPipeline", "FileDetector", "FileType"]
