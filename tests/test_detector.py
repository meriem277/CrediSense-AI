"""
tests/test_detector.py
Teste que le détecteur reconnaît correctement chaque type de fichier.
Les données viennent de tests/conftest.py (chargées depuis tests/samples/).
"""

import pytest
from pathlib import Path
from ocr.detector import FileDetector, FileType


@pytest.fixture(scope="module")
def detector():
    return FileDetector()


class TestImages:

    def test_releve_jpg_est_image(self, detector, img_releve_jpg):
        assert detector.detect(img_releve_jpg) == FileType.IMAGE

    def test_bulletin_png_est_image(self, detector, img_bulletin_png):
        assert detector.detect(img_bulletin_png) == FileType.IMAGE

    def test_cni_jpg_est_image(self, detector, img_cni_jpg):
        assert detector.detect(img_cni_jpg) == FileType.IMAGE

    def test_mauvaise_qualite_est_image(self, detector, img_mauvaise_qualite):
        assert detector.detect(img_mauvaise_qualite) == FileType.IMAGE

    def test_toutes_images_sont_IMAGE(self, detector, toutes_les_images):
        """Test paramétré : toutes les images doivent retourner IMAGE."""
        for img_path in toutes_les_images:
            result = detector.detect(img_path)
            assert result == FileType.IMAGE, f"Echec sur {img_path.name} : obtenu {result}"


class TestPDFsNatifs:

    def test_contrat_est_pdf_natif(self, detector, pdf_contrat):
        assert detector.detect(pdf_contrat) == FileType.PDF_NATIVE

    def test_dossier_est_pdf_natif(self, detector, pdf_dossier):
        assert detector.detect(pdf_dossier) == FileType.PDF_NATIVE

    def test_imposition_est_pdf_natif(self, detector, pdf_imposition):
        assert detector.detect(pdf_imposition) == FileType.PDF_NATIVE

    def test_tous_pdfs_natifs(self, detector, tous_les_pdfs_natifs):
        for pdf_path in tous_les_pdfs_natifs:
            result = detector.detect(pdf_path)
            assert result == FileType.PDF_NATIVE, f"Echec sur {pdf_path.name} : obtenu {result}"


class TestPDFsNatifs:

    def test_contrat_est_pdf_natif(self, detector, pdf_contrat):
        assert detector.detect(pdf_contrat) == FileType.PDF_NATIVE

    def test_dossier_est_pdf_natif(self, detector, pdf_dossier):
        assert detector.detect(pdf_dossier) == FileType.PDF_NATIVE

    def test_imposition_est_pdf_natif(self, detector, pdf_imposition):
        assert detector.detect(pdf_imposition) == FileType.PDF_NATIVE

    def test_tous_pdfs_natifs(self, detector, tous_les_pdfs_natifs):
        for pdf_path in tous_les_pdfs_natifs:
            result = detector.detect(pdf_path)
            assert result == FileType.PDF_NATIVE, f"Echec sur {pdf_path.name} : obtenu {result}"


class TestPDFsNatifs:

    def test_contrat_est_pdf_natif(self, detector, pdf_contrat):
        assert detector.detect(pdf_contrat) == FileType.PDF_NATIVE

    def test_dossier_est_pdf_natif(self, detector, pdf_dossier):
        assert detector.detect(pdf_dossier) == FileType.PDF_NATIVE

    def test_imposition_est_pdf_natif(self, detector, pdf_imposition):
        assert detector.detect(pdf_imposition) == FileType.PDF_NATIVE

    def test_tous_pdfs_natifs(self, detector, tous_les_pdfs_natifs):
        for pdf_path in tous_les_pdfs_natifs:
            result = detector.detect(pdf_path)
            assert result == FileType.PDF_NATIVE, f"Echec sur {pdf_path.name} : obtenu {result}"


class TestPDFsScannes:

    def test_releve_scanne_est_pdf_scanne(self, detector, pdf_releve_scanne):
        assert detector.detect(pdf_releve_scanne) == FileType.PDF_SCANNED

    def test_bulletin_scanne_est_pdf_scanne(self, detector, pdf_bulletin_scanne):
        assert detector.detect(pdf_bulletin_scanne) == FileType.PDF_SCANNED

    def test_tous_pdfs_scannes(self, detector, tous_les_pdfs_scannes):
        for pdf_path in tous_les_pdfs_scannes:
            result = detector.detect(pdf_path)
            assert result == FileType.PDF_SCANNED, f"Echec sur {pdf_path.name} : obtenu {result}"


class TestCasErreur:

    def test_fichier_inexistant(self, detector):
        with pytest.raises(FileNotFoundError):
            detector.detect("C:/fichier/inexistant.pdf")

    def test_fichier_vide(self, detector, tmp_path):
        f = tmp_path / "vide.jpg"
        f.write_bytes(b"")
        with pytest.raises(ValueError):
            detector.detect(f)

    def test_format_non_supporte(self, detector, tmp_path):
        f = tmp_path / "tableau.xlsx"
        f.write_text("fake")
        assert detector.detect(f) == FileType.UNSUPPORTED

    def test_retourne_toujours_filetype(self, detector, img_releve_jpg):
        assert isinstance(detector.detect(img_releve_jpg), FileType)