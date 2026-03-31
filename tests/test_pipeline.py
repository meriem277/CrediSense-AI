"""
Test d'intégration — teste le pipeline complet de la Couche 1.

Ce test vérifie que pour un fichier donné, le pipeline retourne
bien un résultat avec du texte, sans erreur.
"""

import pytest
from pathlib import Path
from ocr.pipeline import OCRPipeline

SAMPLES_DIR = Path("tests/samples")

@pytest.fixture(scope="module")
def pipeline():
    """Crée une seule instance du pipeline pour tous les tests du module."""
    return OCRPipeline()


class TestOCRPipeline:
    
    def test_pipeline_returns_dict(self, pipeline, tmp_path):
        """Le pipeline doit toujours retourner un dictionnaire."""
        from PIL import Image, ImageDraw
        
        # Créer une image avec du texte
        img = Image.new("RGB", (400, 100), color=(255, 255, 255))
        draw = ImageDraw.Draw(img)
        draw.text((10, 10), "Salaire net : 2500 DT", fill=(0, 0, 0))
        img_path = tmp_path / "test.jpg"
        img.save(img_path)
        
        result = pipeline.process(img_path)
        
        # Vérifie que toutes les clés obligatoires sont présentes
        assert isinstance(result, dict)
        assert "text" in result
        assert "success" in result
        assert "extraction_method" in result
        assert "source" in result
        assert "file_type" in result
    
    def test_pipeline_unsupported_file(self, pipeline, tmp_path):
        """Un format non supporté doit retourner success=False."""
        fake_file = tmp_path / "document.xlsx"
        fake_file.write_text("contenu fake")
        
        result = pipeline.process(fake_file)
        
        assert result["success"] == False
        assert result["text"] == ""
        assert result["error"] is not None
    
    def test_pipeline_missing_file(self, pipeline):
        """Un fichier manquant doit lever FileNotFoundError."""
        with pytest.raises(FileNotFoundError):
            pipeline.process("/fichier/qui/nexiste/pas.pdf")
    
    def test_result_has_text_for_image(self, pipeline, tmp_path):
        """Pour une vraie image, le texte extrait ne doit pas être vide."""
        from PIL import Image, ImageDraw, ImageFont
        
        img = Image.new("RGB", (500, 200), color=(255, 255, 255))
        draw = ImageDraw.Draw(img)
        draw.text((20, 50), "Dossier Credit - Banque Nationale", fill=(0, 0, 0))
        draw.text((20, 100), "Montant demande: 50000 DT", fill=(0, 0, 0))
        img_path = tmp_path / "dossier.png"
        img.save(img_path)
        
        result = pipeline.process(img_path)
        
        assert result["success"] == True
        assert len(result["text"]) > 0