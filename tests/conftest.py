import pytest
from pathlib import Path
from PIL import Image
import fitz  # PyMuPDF

# =========================
# IMAGES FIXTURES
# =========================

@pytest.fixture
def img_releve_jpg(tmp_path):
    path = tmp_path / "releve.jpg"
    Image.new("RGB", (100, 100)).save(path)
    return path

@pytest.fixture
def img_bulletin_png(tmp_path):
    path = tmp_path / "bulletin.png"
    Image.new("RGB", (100, 100)).save(path)
    return path

@pytest.fixture
def img_cni_jpg(tmp_path):
    path = tmp_path / "cni.jpg"
    Image.new("RGB", (100, 100)).save(path)
    return path

@pytest.fixture
def img_mauvaise_qualite(tmp_path):
    path = tmp_path / "low_quality.jpg"
    Image.new("RGB", (10, 10)).save(path)
    return path

@pytest.fixture
def toutes_les_images(
    img_releve_jpg, img_bulletin_png, img_cni_jpg, img_mauvaise_qualite
):
    return [img_releve_jpg, img_bulletin_png, img_cni_jpg, img_mauvaise_qualite]

# =========================
# PDF NATIFS
# =========================

def create_pdf_with_text(path):
    doc = fitz.open()
    page = doc.new_page()
    page.insert_text((72, 72), "Hello world")  # texte natif
    doc.save(path)
    doc.close()

@pytest.fixture
def pdf_contrat(tmp_path):
    path = tmp_path / "contrat.pdf"
    create_pdf_with_text(path)
    return path

@pytest.fixture
def pdf_dossier(tmp_path):
    path = tmp_path / "dossier.pdf"
    create_pdf_with_text(path)
    return path

@pytest.fixture
def pdf_imposition(tmp_path):
    path = tmp_path / "imposition.pdf"
    create_pdf_with_text(path)
    return path

@pytest.fixture
def tous_les_pdfs_natifs(pdf_contrat, pdf_dossier, pdf_imposition):
    return [pdf_contrat, pdf_dossier, pdf_imposition]

# =========================
# PDF SCANNÉS
# =========================

def create_scanned_pdf(path):
    doc = fitz.open()
    page = doc.new_page()
    pix = fitz.Pixmap(fitz.csRGB, (0, 0, 100, 100))
    page.insert_image(page.rect, pixmap=pix)
    doc.save(path)
    doc.close()

@pytest.fixture
def pdf_releve_scanne(tmp_path):
    path = tmp_path / "releve_scan.pdf"
    create_scanned_pdf(path)
    return path

@pytest.fixture
def pdf_bulletin_scanne(tmp_path):
    path = tmp_path / "bulletin_scan.pdf"
    create_scanned_pdf(path)
    return path

@pytest.fixture
def tous_les_pdfs_scannes(pdf_releve_scanne, pdf_bulletin_scanne):
    return [pdf_releve_scanne, pdf_bulletin_scanne]