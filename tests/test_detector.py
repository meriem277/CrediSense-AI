"""
test_detector.py — Tests du détecteur de type de fichier.
Toutes les données de test sont générées dans le code, pas besoin de vrais fichiers.
"""

import pytest
from pathlib import Path
from PIL import Image, ImageDraw
import fitz  # PyMuPDF

# L'import utilise maintenant "ocr" (nom réel de ton dossier)
from ocr.detector import FileDetector, FileType


@pytest.fixture
def detector():
    """Instance du détecteur, réutilisée dans tous les tests."""
    return FileDetector()


@pytest.fixture
def image_jpg(tmp_path):
    """Crée une vraie image JPG avec du texte simulant un relevé bancaire."""
    img = Image.new("RGB", (400, 200), color=(255, 255, 255))
    draw = ImageDraw.Draw(img)
    draw.text((20, 20),  "BANQUE NATIONALE - Relevé de compte", fill=(0, 0, 0))
    draw.text((20, 60),  "Client : Mohamed Ben Ali", fill=(0, 0, 0))
    draw.text((20, 100), "Solde : 15 000 DT", fill=(0, 0, 0))
    draw.text((20, 140), "Date  : 01/01/2025", fill=(0, 0, 0))
    path = tmp_path / "releve_bancaire.jpg"
    img.save(path)
    return path


@pytest.fixture
def image_png(tmp_path):
    """Crée une image PNG simulant un bulletin de salaire."""
    img = Image.new("RGB", (500, 300), color=(240, 240, 255))
    draw = ImageDraw.Draw(img)
    draw.text((20, 20),  "BULLETIN DE SALAIRE", fill=(0, 0, 100))
    draw.text((20, 70),  "Salaire brut  : 3 500 DT", fill=(0, 0, 0))
    draw.text((20, 110), "Cotisations   :   350 DT", fill=(0, 0, 0))
    draw.text((20, 150), "Salaire net   : 3 150 DT", fill=(0, 0, 0))
    path = tmp_path / "bulletin_salaire.png"
    img.save(path)
    return path


@pytest.fixture
def pdf_natif(tmp_path):
    """Crée un vrai PDF avec texte natif (simulant un contrat de prêt)."""
    path = tmp_path / "contrat_pret.pdf"
    doc = fitz.open()
    page = doc.new_page(width=595, height=842)  # A4
    page.insert_text(
        (72, 100),
        "CONTRAT DE PRET IMMOBILIER\n\n"
        "Emprunteur : Fatima Zahra Mansouri\n"
        "Montant    : 200 000 DT\n"
        "Durée      : 20 ans\n"
        "Taux       : 7.5%\n\n"
        "Ce contrat lie les deux parties à compter de la date de signature.",
        fontsize=12,
    )
    doc.save(str(path))
    doc.close()
    return path


@pytest.fixture
def pdf_scan_simule(tmp_path):
    """
    Crée un PDF 'scanné' : une image intégrée dans un PDF, sans texte natif.
    Le detector doit le reconnaître comme PDF_SCANNED.
    """
    # D'abord, crée une image
    img = Image.new("RGB", (595, 842), color=(255, 255, 255))
    draw = ImageDraw.Draw(img)
    draw.text((50, 100), "Fiche de paie scannee - texte en image", fill=(0, 0, 0))
    img_path = tmp_path / "scan_temp.png"
    img.save(img_path)

    # Ensuite, insère cette image dans un PDF (sans texte natif)
    pdf_path = tmp_path / "dossier_scanne.pdf"
    doc = fitz.open()
    page = doc.new_page(width=595, height=842)
    rect = fitz.Rect(0, 0, 595, 842)
    page.insert_image(rect, filename=str(img_path))
    doc.save(str(pdf_path))
    doc.close()
    return pdf_path


# ══════════════════════════════════════════════════════════════════════════════
# TESTS
# ══════════════════════════════════════════════════════════════════════════════

class TestFileDetector:

    def test_image_jpg_detectee(self, detector, image_jpg):
        """Une image JPG doit être détectée comme IMAGE."""
        result = detector.detect(image_jpg)
        assert result == FileType.IMAGE, f"Attendu IMAGE, obtenu {result}"

    def test_image_png_detectee(self, detector, image_png):
        """Une image PNG doit être détectée comme IMAGE."""
        result = detector.detect(image_png)
        assert result == FileType.IMAGE

    def test_pdf_natif_detecte(self, detector, pdf_natif):
        """Un PDF avec texte natif doit être détecté comme PDF_NATIVE."""
        result = detector.detect(pdf_natif)
        assert result == FileType.PDF_NATIVE, (
            f"Attendu PDF_NATIVE, obtenu {result}. "
            "Vérifie que le PDF contient bien du texte."
        )

    def test_pdf_scanne_detecte(self, detector, pdf_scan_simule):
        """Un PDF scanné (image dans PDF) doit être détecté comme PDF_SCANNED."""
        result = detector.detect(pdf_scan_simule)
        assert result == FileType.PDF_SCANNED, (
            f"Attendu PDF_SCANNED, obtenu {result}"
        )

    def test_fichier_inexistant(self, detector):
        """Un fichier qui n'existe pas doit lever FileNotFoundError."""
        with pytest.raises(FileNotFoundError):
            detector.detect("C:/fichier/qui/nexiste/pas.pdf")

    def test_fichier_vide(self, detector, tmp_path):
        """Un fichier vide (0 octets) doit lever ValueError."""
        fichier_vide = tmp_path / "vide.jpg"
        fichier_vide.write_bytes(b"")
        with pytest.raises(ValueError):
            detector.detect(fichier_vide)

    def test_format_non_supporte(self, detector, tmp_path):
        """Un fichier .xlsx doit retourner UNSUPPORTED."""
        fake_excel = tmp_path / "budget.xlsx"
        fake_excel.write_text("contenu quelconque")
        result = detector.detect(fake_excel)
        assert result == FileType.UNSUPPORTED

    def test_retourne_bien_un_filetype(self, detector, image_jpg):
        """Le résultat doit toujours être une instance de FileType."""
        result = detector.detect(image_jpg)
        assert isinstance(result, FileType)