"""
scripts/generate_samples.py
Génère tous les fichiers de test dans tests/samples/.

Lance avec :
    python scripts/generate_samples.py

Fichiers générés :
    releve_bancaire.jpg         image JPG — relevé de compte
    bulletin_salaire.png        image PNG — bulletin de salaire
    piece_identite.jpg          image JPG — CNI simulée
    photo_mauvaise_qualite.jpg  image JPG — scan de mauvaise qualité
    contrat_pret.pdf            PDF natif — contrat de prêt
    dossier_credit.pdf          PDF natif — dossier complet multi-pages
    avis_imposition.pdf         PDF natif — avis d'imposition
    releve_scanne.pdf           PDF scanné — image dans PDF
    bulletin_scanne.pdf         PDF scanné — image dans PDF
"""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter
import fitz  # PyMuPDF
import random

SAMPLES_DIR = Path("tests/samples")
SAMPLES_DIR.mkdir(parents=True, exist_ok=True)


def log(msg):
    print(f"  ✓  {msg}")


# ─────────────────────────────────────────────────────────────────────────────
# IMAGES JPG / PNG
# ─────────────────────────────────────────────────────────────────────────────

def make_releve_bancaire():
    """Relevé de compte bancaire — image JPG claire."""
    img = Image.new("RGB", (794, 561), color=(255, 255, 255))
    draw = ImageDraw.Draw(img)

    # En-tête banque
    draw.rectangle([0, 0, 794, 80], fill=(20, 60, 120))
    draw.text((30, 20), "BANQUE NATIONALE DE TUNISIE", fill=(255, 255, 255))
    draw.text((30, 45), "www.bnt.com.tn  |  Tel : 71 100 100", fill=(200, 220, 255))

    # Infos client
    draw.text((30, 100), "RELEVE DE COMPTE", fill=(20, 60, 120))
    draw.line([30, 120, 764, 120], fill=(200, 200, 200), width=1)
    draw.text((30, 135), "Titulaire     : BEN ALI Mohamed", fill=(40, 40, 40))
    draw.text((30, 160), "N° Compte     : 08 104 0012345 67", fill=(40, 40, 40))
    draw.text((30, 185), "Periode       : 01/01/2025 - 31/01/2025", fill=(40, 40, 40))
    draw.text((30, 210), "Solde initial : 12 500,00 DT", fill=(40, 40, 40))

    # Tableau des opérations
    draw.rectangle([30, 240, 764, 265], fill=(230, 240, 255))
    draw.text((35, 248),  "Date",        fill=(20, 60, 120))
    draw.text((150, 248), "Libelle",     fill=(20, 60, 120))
    draw.text((530, 248), "Debit",       fill=(20, 60, 120))
    draw.text((640, 248), "Credit",      fill=(20, 60, 120))

    operations = [
        ("05/01/2025", "Virement salaire - Societe ABC",      "",          "3 150,00"),
        ("08/01/2025", "Paiement loyer appartement",          "1 200,00",  ""),
        ("10/01/2025", "Retrait DAB - Agence Lac 1",          "500,00",    ""),
        ("15/01/2025", "Reglement facture STEG",              "85,50",     ""),
        ("20/01/2025", "Virement recu - Client Durand",       "",          "2 000,00"),
        ("25/01/2025", "Paiement assurance voiture",          "320,00",    ""),
        ("28/01/2025", "Retrait especes",                     "300,00",    ""),
        ("31/01/2025", "Frais tenue de compte",               "8,00",      ""),
    ]

    y = 270
    for i, (date, libelle, debit, credit) in enumerate(operations):
        bg = (255, 255, 255) if i % 2 == 0 else (248, 248, 252)
        draw.rectangle([30, y, 764, y + 22], fill=bg)
        draw.text((35, y + 4),   date,    fill=(60, 60, 60))
        draw.text((150, y + 4),  libelle, fill=(60, 60, 60))
        draw.text((530, y + 4),  debit,   fill=(180, 40, 40))
        draw.text((640, y + 4),  credit,  fill=(40, 140, 40))
        y += 22

    # Solde final
    draw.line([30, y + 5, 764, y + 5], fill=(20, 60, 120), width=2)
    draw.text((30, y + 15),  "SOLDE FINAL AU 31/01/2025 :", fill=(20, 60, 120))
    draw.text((530, y + 15), "15 236,50 DT",               fill=(20, 60, 120))

    path = SAMPLES_DIR / "releve_bancaire.jpg"
    img.save(path, quality=95)
    log(f"releve_bancaire.jpg")
    return path


def make_bulletin_salaire():
    """Bulletin de salaire — image PNG."""
    img = Image.new("RGB", (794, 600), color=(255, 255, 255))
    draw = ImageDraw.Draw(img)

    draw.rectangle([0, 0, 794, 70], fill=(40, 100, 60))
    draw.text((30, 15), "SOCIETE ABC SARL",              fill=(255, 255, 255))
    draw.text((30, 40), "MF : 1234567/A | RIB : BNA 08 104 9999", fill=(200, 240, 200))

    draw.text((30, 90),  "BULLETIN DE PAIE — JANVIER 2025", fill=(40, 100, 60))
    draw.line([30, 115, 764, 115], fill=(180, 180, 180), width=1)

    draw.text((30, 130),  "Employe    : MANSOURI Fatima Zahra",   fill=(40, 40, 40))
    draw.text((30, 155),  "Poste      : Ingenieure Informatique", fill=(40, 40, 40))
    draw.text((30, 180),  "CIN        : 12345678",                fill=(40, 40, 40))
    draw.text((30, 205),  "CNSS       : 987654321",               fill=(40, 40, 40))
    draw.text((400, 130), "Periode    : 01/01/2025 - 31/01/2025", fill=(40, 40, 40))
    draw.text((400, 155), "Matricule  : EMP-2019-047",            fill=(40, 40, 40))

    draw.rectangle([30, 240, 764, 265], fill=(220, 240, 220))
    draw.text((35, 248),   "Rubrique",          fill=(40, 100, 60))
    draw.text((450, 248),  "Brut (DT)",         fill=(40, 100, 60))
    draw.text((600, 248),  "Net (DT)",          fill=(40, 100, 60))

    rubriques = [
        ("Salaire de base",           "3 000,00", ""),
        ("Prime d anciennete (5%)",   "150,00",   ""),
        ("Prime de rendement",        "350,00",   ""),
        ("CNSS employe (9.18%)",      "",         "-320,30"),
        ("IRPP (tranche 2)",          "",         "-180,00"),
        ("Avance sur salaire",        "",         "-200,00"),
    ]

    y = 270
    for i, (rubrique, brut, retenue) in enumerate(rubriques):
        bg = (255, 255, 255) if i % 2 == 0 else (245, 252, 245)
        draw.rectangle([30, y, 764, y + 22], fill=bg)
        draw.text((35, y + 4),   rubrique, fill=(60, 60, 60))
        draw.text((450, y + 4),  brut,     fill=(40, 40, 40))
        draw.text((600, y + 4),  retenue,  fill=(180, 40, 40))
        y += 22

    draw.line([30, y + 8, 764, y + 8], fill=(40, 100, 60), width=2)
    draw.rectangle([30, y + 15, 764, y + 45], fill=(230, 250, 230))
    draw.text((35, y + 25),   "NET A PAYER :", fill=(40, 100, 60))
    draw.text((600, y + 25),  "2 799,70 DT",  fill=(40, 100, 60))

    path = SAMPLES_DIR / "bulletin_salaire.png"
    img.save(path)
    log("bulletin_salaire.png")
    return path


def make_piece_identite():
    """Carte Nationale d'Identité simulée — image JPG."""
    img = Image.new("RGB", (640, 400), color=(245, 240, 220))
    draw = ImageDraw.Draw(img)

    draw.rectangle([0, 0, 640, 400], outline=(100, 80, 40), width=8)
    draw.rectangle([0, 0, 640, 60], fill=(180, 20, 20))
    draw.text((220, 15), "REPUBLIQUE TUNISIENNE", fill=(255, 255, 200))
    draw.text((190, 38), "CARTE NATIONALE D IDENTITE", fill=(255, 240, 150))

    draw.rectangle([30, 80, 160, 200], fill=(200, 195, 185), outline=(100, 80, 40))
    draw.text((75, 130), "PHOTO", fill=(100, 80, 40))

    draw.text((190, 90),  "Nom       : BEN SALAH",   fill=(20, 20, 80))
    draw.text((190, 115), "Prenom    : Karim",        fill=(20, 20, 80))
    draw.text((190, 140), "Naissance : 15/03/1985",   fill=(20, 20, 80))
    draw.text((190, 165), "Lieu      : Tunis",        fill=(20, 20, 80))
    draw.text((190, 190), "CIN       : 09876543",     fill=(20, 20, 80))

    draw.text((30, 230),  "Date delivrance : 10/06/2020", fill=(40, 40, 40))
    draw.text((30, 255),  "Date expiration : 09/06/2030", fill=(40, 40, 40))

    # Zone de lecture machine (MRZ simulée)
    draw.rectangle([0, 340, 640, 400], fill=(230, 230, 230))
    draw.text((20, 350), "IDFRA BEN SALAH<<KARIM<<<<<<<<<<<<<<<<<<<<<<", fill=(0, 0, 0))
    draw.text((20, 370), "0987654321TUN8503154M3006096<<<<<<<<<<<<<<<2", fill=(0, 0, 0))

    path = SAMPLES_DIR / "piece_identite.jpg"
    img.save(path, quality=90)
    log("piece_identite.jpg")
    return path


def make_image_mauvaise_qualite():
    """Image de mauvaise qualité — simule une photo prise avec un smartphone."""
    img = Image.new("RGB", (600, 400), color=(240, 235, 220))
    draw = ImageDraw.Draw(img)

    draw.text((30, 30),  "Attestation de Travail",   fill=(30, 30, 60))
    draw.text((30, 70),  "M. TRABELSI Ahmed",        fill=(40, 40, 40))
    draw.text((30, 100), "occupe le poste de",       fill=(40, 40, 40))
    draw.text((30, 130), "Directeur Commercial",     fill=(40, 40, 40))
    draw.text((30, 180), "Salaire : 4 200 DT / mois",fill=(40, 40, 40))
    draw.text((30, 220), "Anciennete : 8 ans",       fill=(40, 40, 40))
    draw.text((30, 300), "Fait a Tunis le 15/01/2025", fill=(60, 60, 60))
    draw.text((30, 330), "Cachet et signature",      fill=(80, 80, 80))

    # Ajouter du bruit pour simuler mauvaise qualité
    import random
    pixels = img.load()
    for _ in range(3000):
        x = random.randint(0, 599)
        y = random.randint(0, 399)
        noise = random.randint(-30, 30)
        r, g, b = pixels[x, y]
        pixels[x, y] = (
            max(0, min(255, r + noise)),
            max(0, min(255, g + noise)),
            max(0, min(255, b + noise)),
        )

    # Légère rotation (photo prise de travers)
    img = img.rotate(3, fillcolor=(255, 255, 255))
    # Flou léger
    img = img.filter(ImageFilter.GaussianBlur(radius=0.8))

    path = SAMPLES_DIR / "photo_mauvaise_qualite.jpg"
    img.save(path, quality=60)  # Compression basse = mauvaise qualité
    log("photo_mauvaise_qualite.jpg")
    return path


# ─────────────────────────────────────────────────────────────────────────────
# PDFs NATIFS (avec texte extractible directement)
# ─────────────────────────────────────────────────────────────────────────────

def make_contrat_pret():
    """PDF natif — contrat de prêt immobilier complet."""
    path = SAMPLES_DIR / "contrat_pret.pdf"
    doc = fitz.open()

    # Page 1
    page = doc.new_page(width=595, height=842)
    contenu = """CONTRAT DE PRET IMMOBILIER

Banque    : Banque Nationale de Tunisie (BNT)
Agence    : Lac 1 - Tunis

EMPRUNTEUR
Nom       : MANSOURI Fatima Zahra
CIN       : 12345678
Adresse   : 15 Rue Ibn Khaldoun, Tunis 1001
Tel       : +216 71 234 567

CONDITIONS DU PRET
Montant demande    : 200 000 DT
Duree              : 240 mois (20 ans)
Taux d interet     : 7,5% annuel
Mensualite         : 1 609,25 DT
Assurance mensuelle:   120,00 DT
Mensualite totale  : 1 729,25 DT

GARANTIES
- Hypotheque de premier rang sur bien immobilier
- Assurance vie et invalidite obligatoire
- Caution personnelle du conjoint

BIEN FINANCE
Nature    : Appartement
Adresse   : Cite El Menzah 9, Ariana
Superficie: 120 m2
Valeur    : 280 000 DT

Lu et approuve - Fait a Tunis le 15/01/2025
"""
    page.insert_text((72, 72), contenu, fontsize=11)

    # Page 2 — Tableau d'amortissement (premières lignes)
    page2 = doc.new_page(width=595, height=842)
    tableau = """TABLEAU D AMORTISSEMENT (extrait)

Mois  Capital restant   Interet   Capital    Mensualite
 1      200 000,00      1 250,00   359,25     1 609,25
 2      199 640,75      1 247,75   361,50     1 609,25
 3      199 279,25      1 245,50   363,75     1 609,25
 4      198 915,50      1 243,22   366,03     1 609,25
 5      198 549,47      1 240,93   368,32     1 609,25
 6      198 181,15      1 238,63   370,62     1 609,25
12      195 732,18      1 223,33   385,92     1 609,25
24      191 108,45      1 194,43   414,82     1 609,25
60      178 234,12      1 113,96   495,29     1 609,25
120     147 892,34        924,33   684,92     1 609,25
240           0,00          0,00     0,00     1 609,25

Total interets payes : 186 220,00 DT
Cout total du credit : 386 220,00 DT
"""
    page2.insert_text((72, 72), tableau, fontsize=10)

    doc.save(str(path))
    doc.close()
    log("contrat_pret.pdf (PDF natif, 2 pages)")
    return path


def make_dossier_credit():
    """PDF natif multi-pages — dossier de crédit complet."""
    path = SAMPLES_DIR / "dossier_credit.pdf"
    doc = fitz.open()

    pages_content = [
        ("RESUME DOSSIER CREDIT", """
REFERENCE DOSSIER : DC-2025-00892

CLIENT
Nom complet  : TRABELSI Ahmed Karim
Date naissance: 12/07/1980  (44 ans)
Situation    : Marie - 2 enfants
Adresse      : 22 Avenue Habib Bourguiba, Sfax 3000
Tel          : +216 98 765 432
Email        : ahmed.trabelsi@email.tn

EMPLOI
Employeur    : SOCIETE TUNISIENNE D INFORMATIQUE (STI)
Poste        : Directeur des Systemes d Information
Anciennete   : 12 ans
Type contrat : CDI (Contrat a Duree Indeterminee)
Salaire net  : 4 200 DT / mois

DEMANDE
Type credit  : Credit Immobilier
Montant      : 350 000 DT
Duree        : 25 ans (300 mois)
Bien         : Villa - La Marsa, Tunis
"""),
        ("ANALYSE FINANCIERE", """
REVENUS MENSUELS NETS
Salaire net employe     : 4 200,00 DT
Revenus locatifs        :   800,00 DT
Autres revenus          :   200,00 DT
TOTAL REVENUS           : 5 200,00 DT

CHARGES MENSUELLES ACTUELLES
Loyer actuel            :   700,00 DT
Credit voiture          :   450,00 DT
Autres credits          :   250,00 DT
Charges famille         :   600,00 DT
TOTAL CHARGES           : 2 000,00 DT

CALCUL TAUX D ENDETTEMENT
Charges actuelles       : 2 000,00 DT
Mensualite nouveau pret : 2 450,00 DT (estimee)
Total avec nouveau pret : 4 450,00 DT
Revenus                 : 5 200,00 DT

TAUX D ENDETTEMENT = 4 450 / 5 200 = 85,6%
SEUIL REGLEMENTAIRE    : 40%

CONCLUSION : Taux d endettement SUPERIEUR au seuil autorise.
RECOMMANDATION : CREDIT REFUSE - ratio endettement excessif.
"""),
        ("HISTORIQUE BANCAIRE", """
COMPORTEMENT BANCAIRE (24 derniers mois)

Incidents de paiement   : 2 incidents mineurs
Cheques sans provision  : 0
Dernier incident        : 03/2024 - regularise
Notation interne        : B+ (Bon avec reserves)

CREDITS EN COURS
Credit voiture BNT      : solde 18 000 DT (reste 24 mois)
Credit personnel ATB    : solde  6 500 DT (reste 18 mois)

EPARGNE
Compte epargne          : 35 000 DT
PEL (Plan Epargne Logement): 22 000 DT
Total epargne           : 57 000 DT

APPORT PERSONNEL DISPONIBLE : 57 000 DT (16,3% du bien)
Apport minimum requis        : 20% = 70 000 DT
INSUFFISANT - apport inferieur au minimum requis.
"""),
    ]

    for titre, contenu in pages_content:
        page = doc.new_page(width=595, height=842)
        page.insert_text((72, 50), titre, fontsize=14)
        page.insert_text((72, 90), contenu, fontsize=10)

    doc.save(str(path))
    doc.close()
    log("dossier_credit.pdf (PDF natif, 3 pages)")
    return path


def make_avis_imposition():
    """PDF natif — avis d'imposition simulé."""
    path = SAMPLES_DIR / "avis_imposition.pdf"
    doc = fitz.open()
    page = doc.new_page(width=595, height=842)
    contenu = """DIRECTION GENERALE DES IMPOTS
RECETTE DES FINANCES DE TUNIS

AVIS D IMPOSITION SUR LE REVENU 2024

Contribuable  : MANSOURI Fatima Zahra
Adresse       : 15 Rue Ibn Khaldoun, Tunis 1001
CIN           : 12345678
N° Fiscal     : 987654/B

REVENUS DECLARES
Traitements et salaires     : 37 800,00 DT
Autres revenus              :  2 400,00 DT
REVENU GLOBAL BRUT          : 40 200,00 DT

DEDUCTIONS
Deduction chef de famille   :    300,00 DT
REVENU IMPOSABLE            : 39 900,00 DT

CALCUL IMPOT
Tranche 1 (0-5000)    : 0%  =     0,00 DT
Tranche 2 (5001-20000): 26% = 3 900,00 DT
Tranche 3 (20001-39900):28% = 5 572,00 DT
IMPOT TOTAL                 : 9 472,00 DT
Retenue a la source         : 9 472,00 DT
SOLDE                       :     0,00 DT

Fait a Tunis, le 20 mars 2025
Receveur des Finances : M. GHARBI Hamed
"""
    page.insert_text((72, 72), contenu, fontsize=11)
    doc.save(str(path))
    doc.close()
    log("avis_imposition.pdf (PDF natif, 1 page)")
    return path


# ─────────────────────────────────────────────────────────────────────────────
# PDFs SCANNÉS (image insérée dans un PDF, sans texte natif)
# ─────────────────────────────────────────────────────────────────────────────

def _image_to_pdf(img: Image.Image, pdf_path: Path, tmp_name: str):
    """Helper : insère une image dans un PDF (simule un scan)."""
    tmp_img_path = SAMPLES_DIR / tmp_name
    img.save(tmp_img_path, quality=85)

    doc = fitz.open()
    page = doc.new_page(width=595, height=842)
    page.insert_image(fitz.Rect(20, 20, 575, 822), filename=str(tmp_img_path))
    doc.save(str(pdf_path))
    doc.close()
    tmp_img_path.unlink()  # Supprime l'image temporaire


def make_releve_scanne():
    """PDF scanné — relevé bancaire en image dans un PDF."""
    img = Image.new("RGB", (794, 561), color=(255, 255, 255))
    draw = ImageDraw.Draw(img)
    draw.rectangle([0, 0, 794, 60], fill=(20, 60, 120))
    draw.text((30, 18), "BANQUE DE L HABITAT - RELEVE DE COMPTE", fill=(255, 255, 255))
    draw.text((30, 90),  "Client  : GHARBI Mohamed Ali",             fill=(30, 30, 30))
    draw.text((30, 120), "Compte  : 17 006 0087654 32",              fill=(30, 30, 30))
    draw.text((30, 150), "Solde   : 8 430,50 DT",                    fill=(30, 30, 30))
    draw.text((30, 200), "Operations du mois :",                     fill=(20, 60, 120))
    draw.text((30, 230), "10/01  Virement Salaire    +2 800,00 DT",  fill=(30, 30, 30))
    draw.text((30, 258), "12/01  Paiement loyer       -900,00 DT",   fill=(30, 30, 30))
    draw.text((30, 286), "20/01  Retrait DAB          -500,00 DT",   fill=(30, 30, 30))
    draw.text((30, 314), "28/01  Virement recu      +1 200,00 DT",   fill=(30, 30, 30))
    draw.text((30, 400), "Solde final : 11 030,50 DT",               fill=(20, 60, 120))

    # Ajouter bruit léger pour simuler scan
    pixels = img.load()
    for _ in range(1500):
        x, y = random.randint(0, 793), random.randint(0, 560)
        n = random.randint(-15, 15)
        r, g, b = pixels[x, y]
        pixels[x, y] = (max(0, min(255, r+n)), max(0, min(255, g+n)), max(0, min(255, b+n)))

    path = SAMPLES_DIR / "releve_scanne.pdf"
    _image_to_pdf(img, path, "_tmp_releve.jpg")
    log("releve_scanne.pdf (PDF scanné)")
    return path


def make_bulletin_scanne():
    """PDF scanné — bulletin de salaire en image dans un PDF."""
    img = Image.new("RGB", (794, 600), color=(255, 255, 255))
    draw = ImageDraw.Draw(img)
    draw.rectangle([0, 0, 794, 55], fill=(60, 120, 60))
    draw.text((30, 15), "ENTREPRISE BATI-PLUS SARL - BULLETIN DE PAIE", fill=(255, 255, 255))
    draw.text((30, 85),  "Employe    : AYARI Sami",           fill=(30, 30, 30))
    draw.text((30, 115), "Poste      : Chef de chantier",     fill=(30, 30, 30))
    draw.text((30, 145), "Periode    : Fevrier 2025",         fill=(30, 30, 30))
    draw.text((30, 195), "Salaire brut       : 2 800,00 DT",  fill=(30, 30, 30))
    draw.text((30, 225), "CNSS (9,18%)       :  -257,04 DT",  fill=(30, 30, 30))
    draw.text((30, 255), "IRPP               :  -145,00 DT",  fill=(30, 30, 30))
    draw.text((30, 285), "Transport          :   +80,00 DT",  fill=(30, 30, 30))
    draw.rectangle([30, 330, 764, 360], fill=(220, 245, 220))
    draw.text((35, 338),  "NET A PAYER        : 2 477,96 DT", fill=(30, 100, 30))
    draw.text((30, 400), "Signature employeur :",             fill=(80, 80, 80))
    draw.text((30, 500), "Cachet de la societe",              fill=(80, 80, 80))

    path = SAMPLES_DIR / "bulletin_scanne.pdf"
    _image_to_pdf(img, path, "_tmp_bulletin.jpg")
    log("bulletin_scanne.pdf (PDF scanné)")
    return path


# ─────────────────────────────────────────────────────────────────────────────
# LANCEMENT
# ─────────────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    print("\n Génération des fichiers de test dans tests/samples/\n")

    make_releve_bancaire()
    make_bulletin_salaire()
    make_piece_identite()
    make_image_mauvaise_qualite()
    make_contrat_pret()
    make_dossier_credit()
    make_avis_imposition()
    make_releve_scanne()
    make_bulletin_scanne()

    print(f"\n Tous les fichiers sont dans : tests/samples/")
    print(" Lance maintenant : pytest tests/ -v\n")