# tests/generer_documents_test.py
"""
Génère un jeu de documents de test FICTIFS pour évaluer la pipeline complète
(extraction / OCR + classification) de CrediSense.

- PDF natifs  : texte réel (couche texte) → l'OCR doit pouvoir être évité
- PDF scannés : image seule, légèrement inclinée, floutée et bruitée → OCR obligatoire

Toutes les données (noms, numéros, sociétés) sont inventées.
Nom des fichiers : <CATEGORIE>__<description>.pdf

Utilisation (depuis Python\\) :
    pip install reportlab pillow arabic-reshaper python-bidi
    python tests/generer_documents_test.py
→ crée tests/documents/
"""

import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont
from reportlab.lib.pagesizes import A4
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas

try:
    import arabic_reshaper
    from bidi.algorithm import get_display
    ARABE_OK = True
except ImportError:
    ARABE_OK = False

SORTIE = Path(__file__).resolve().parent / "documents"
random.seed(42)


# ── Police (Windows / Linux / macOS) ──────────────────────────────────────────
def trouver_police(gras: bool = False) -> str:
    candidats = (
        ["C:/Windows/Fonts/arialbd.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
         "/Library/Fonts/Arial Bold.ttf"]
        if gras else
        ["C:/Windows/Fonts/arial.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
         "/Library/Fonts/Arial.ttf"]
    )
    for c in candidats:
        if Path(c).exists():
            return c
    raise FileNotFoundError("Aucune police TrueType trouvée (Arial / DejaVu Sans).")


POLICE      = trouver_police()
POLICE_GRAS = trouver_police(gras=True)
pdfmetrics.registerFont(TTFont("Doc", POLICE))
pdfmetrics.registerFont(TTFont("DocGras", POLICE_GRAS))


def ar(texte: str) -> str:
    """Mise en forme de l'arabe (liaison des lettres + sens de lecture)."""
    if not ARABE_OK:
        return texte
    return get_display(arabic_reshaper.reshape(texte))


# ── Contenu des documents (FICTIF) ────────────────────────────────────────────
# Chaque document : (fichier, scanné ?, arabe ?, [pages], où page = [(style, texte)])
# style : "titre" | "sous" | "texte"
def P(*lignes):
    return list(lignes)

DOCUMENTS = [
    # ── PDF natifs ───────────────────────────────────────────────────────────
    ("FICHE_PAIE__natif", False, False, [P(
        ("titre", "BULLETIN DE PAIE"),
        ("sous", "OMEGA SERVICES SARL — 45 avenue Habib Bourguiba, Sousse"),
        ("texte", "Période : août 2026            Matricule : 2087"),
        ("texte", "Salarié : Mme Sana TRABELSI   —   Poste : Chargée de clientèle"),
        ("texte", "Date d'embauche : 15/03/2020   —   Contrat : CDI"),
        ("texte", ""),
        ("texte", "Salaire de base ........................................ 2 600,000"),
        ("texte", "Indemnité de transport ................................... 90,000"),
        ("texte", "Prime de présence ....................................... 150,000"),
        ("texte", "SALAIRE BRUT ........................................... 2 840,000"),
        ("texte", "Cotisation CNSS (9,18 %) ............................... 260,712"),
        ("texte", "Retenue à la source IRPP ............................... 298,500"),
        ("texte", "NET À PAYER ............................................ 2 280,788 DT"),
    )]),
    ("RELEVE_BANCAIRE__natif_2pages", False, False, [
        P(("titre", "RELEVÉ DE COMPTE"),
          ("sous", "Banque Fictive de Tunisie — Agence Sousse Centre"),
          ("texte", "Titulaire : Mme Sana TRABELSI"),
          ("texte", "Compte n° 99 123 4567890123456 78"),
          ("texte", "Période du 01/08/2026 au 31/08/2026"),
          ("texte", ""),
          ("texte", "Date     Libellé                                   Débit      Crédit"),
          ("texte", "01/08    Ancien solde créditeur                                1 845,320"),
          ("texte", "03/08    VIREMENT SALAIRE OMEGA SERVICES                       2 280,788"),
          ("texte", "06/08    RETRAIT DAB SOUSSE                     300,000"),
          ("texte", "10/08    PRELEVEMENT STEG                        74,450"),
          ("texte", "12/08    PAIEMENT CARTE MONOPRIX                 126,900")),
        P(("sous", "RELEVÉ DE COMPTE — page 2/2"),
          ("texte", "18/08    ECHEANCE PRET AUTO                     410,000"),
          ("texte", "22/08    VIREMENT RECU                                           150,000"),
          ("texte", "28/08    PRELEVEMENT SONEDE                      28,300"),
          ("texte", ""),
          ("texte", "Total des mouvements :              939,650      2 430,788"),
          ("texte", "Nouveau solde créditeur au 31/08/2026 :             3 336,458 DT")),
    ]),
    ("ATTESTATION_EMPLOI__natif_fr", False, False, [P(
        ("titre", "ATTESTATION DE TRAVAIL"),
        ("texte", "Nous soussignés, OMEGA SERVICES SARL, société au capital de 200 000 DT,"),
        ("texte", "attestons par la présente que Madame Sana TRABELSI, titulaire de la"),
        ("texte", "carte d'identité nationale n° 07654321, est employée au sein de notre"),
        ("texte", "société depuis le 15/03/2020 en qualité de chargée de clientèle,"),
        ("texte", "dans le cadre d'un contrat à durée indéterminée."),
        ("texte", ""),
        ("texte", "Cette attestation est délivrée à l'intéressée, sur sa demande,"),
        ("texte", "pour servir et valoir ce que de droit."),
        ("texte", ""),
        ("texte", "Fait à Sousse, le 02/09/2026            Le Directeur des Ressources Humaines"),
    )]),
    ("ATTESTATION_EMPLOI__natif_ar", False, True, [P(
        ("titre", "شهادة عمل"),
        ("texte", "نحن الممضين أسفله، شركة أوميغا للخدمات،"),
        ("texte", "نشهد أن السيدة سناء الطرابلسي"),
        ("texte", "صاحبة بطاقة التعريف الوطنية عدد 07654321"),
        ("texte", "تعمل بمؤسستنا منذ 15 مارس 2020"),
        ("texte", "بخطة مكلفة بالحرفاء بعقد عمل غير محدد المدة."),
        ("texte", ""),
        ("texte", "سلمت هذه الشهادة للمعنية بالأمر بطلب منها"),
        ("texte", "للإدلاء بها عند الحاجة."),
        ("texte", "سوسة في 02 سبتمبر 2026 — مدير الموارد البشرية"),
    )]),
    ("CONTRAT_TRAVAIL__natif", False, False, [P(
        ("titre", "CONTRAT DE TRAVAIL À DURÉE INDÉTERMINÉE"),
        ("texte", "Entre les soussignés :"),
        ("texte", "OMEGA SERVICES SARL, représentée par son gérant, ci-après « l'employeur »,"),
        ("texte", "et Mme Sana TRABELSI, ci-après « la salariée »."),
        ("texte", ""),
        ("texte", "Article 1 — Engagement : la salariée est engagée en qualité de chargée de clientèle."),
        ("texte", "Article 2 — Période d'essai : six mois à compter de la date d'entrée."),
        ("texte", "Article 3 — Rémunération : salaire mensuel brut de 2 400,000 DT."),
        ("texte", "Article 4 — Durée du travail : 40 heures par semaine."),
        ("texte", "Article 5 — Congés : conformément au Code du travail tunisien."),
        ("texte", "Fait en deux exemplaires, à Sousse, le 15/03/2020."),
    )]),
    ("JUSTIFICATIF_DOMICILE__natif_steg", False, False, [P(
        ("titre", "STEG — FACTURE D'ÉLECTRICITÉ ET DE GAZ"),
        ("sous", "Société Tunisienne de l'Électricité et du Gaz — District Sousse"),
        ("texte", "Abonnée : Mme Sana TRABELSI"),
        ("texte", "Adresse de consommation : 8 rue des Jasmins, Sahloul, Sousse"),
        ("texte", "Référence abonné : 31 457 882"),
        ("texte", "Période : juin — juillet 2026"),
        ("texte", "Consommation électricité : 412 kWh"),
        ("texte", "Consommation gaz : 38 m³"),
        ("texte", "Montant total à payer : 74,450 DT   —   Date limite : 15/08/2026"),
    )]),
    ("DECLARATION_FISCALE__natif", False, False, [P(
        ("titre", "DÉCLARATION ANNUELLE DE L'IMPÔT SUR LE REVENU"),
        ("sous", "Ministère des Finances — Direction Générale des Impôts — Exercice 2025"),
        ("texte", "Contribuable : Mme Sana TRABELSI"),
        ("texte", "Matricule fiscal : 7654321/B/P/000"),
        ("texte", "Revenus de traitements et salaires : 33 600,000"),
        ("texte", "Déductions communes : 2 000,000"),
        ("texte", "Revenu net imposable : 31 600,000"),
        ("texte", "Impôt sur le revenu dû : 5 980,000"),
        ("texte", "Retenues à la source déjà opérées : 5 980,000"),
        ("texte", "Reliquat à payer : 0,000"),
    )]),
    ("AUTRE__natif_menu", False, False, [P(
        ("titre", "Restaurant Le Jasmin — Menu du jour"),
        ("texte", "Entrées : chorba frik, salade tunisienne, brick à l'œuf"),
        ("texte", "Plats : couscous à l'agneau, ojja aux merguez, poisson grillé"),
        ("texte", "Desserts : makroudh, zlabia, salade de fruits"),
        ("texte", "Boissons : thé à la menthe, citronnade, café turc"),
        ("texte", "Ouvert tous les jours de 12 h à 23 h — Réservations : 73 000 000"),
    )]),

    # ── PDF scannés (image seule) ────────────────────────────────────────────
    ("CIN__scan_fr", True, False, [P(
        ("titre", "RÉPUBLIQUE TUNISIENNE"),
        ("sous", "CARTE D'IDENTITÉ NATIONALE"),
        ("texte", "Nom : TRABELSI"),
        ("texte", "Prénom : Sana"),
        ("texte", "Date de naissance : 21/06/1993"),
        ("texte", "Lieu de naissance : Sousse"),
        ("texte", "N° 07654321"),
        ("texte", "Délivrée le : 10/01/2021"),
    )]),
    ("CIN__scan_ar", True, True, [P(
        ("titre", "الجمهورية التونسية"),
        ("sous", "بطاقة التعريف الوطنية"),
        ("texte", "اللقب : الطرابلسي"),
        ("texte", "الاسم : سناء"),
        ("texte", "تاريخ الولادة : 21 جوان 1993"),
        ("texte", "مكانها : سوسة"),
        ("texte", "الرقم : 07654321"),
    )]),
    ("FICHE_PAIE__scan", True, False, [P(
        ("titre", "BULLETIN DE PAIE"),
        ("sous", "DELTA INDUSTRIE SA — Zone industrielle, Monastir"),
        ("texte", "Période : juillet 2026        Matricule : 5521"),
        ("texte", "Salarié : M. Karim JLASSI  —  Technicien supérieur"),
        ("texte", "Salaire de base : 1 950,000"),
        ("texte", "Prime de rendement : 120,000"),
        ("texte", "Salaire brut : 2 070,000"),
        ("texte", "Cotisations CNSS : 190,026"),
        ("texte", "Retenue IRPP : 175,400"),
        ("texte", "Net à payer : 1 704,574 DT"),
    )]),
    ("RELEVE_BANCAIRE__scan", True, False, [P(
        ("titre", "EXTRAIT DE COMPTE"),
        ("sous", "Banque Fictive de Tunisie — Agence Monastir"),
        ("texte", "Titulaire : M. Karim JLASSI"),
        ("texte", "Période du 01/07/2026 au 31/07/2026"),
        ("texte", "Ancien solde : 920,150"),
        ("texte", "05/07  VIR SALAIRE DELTA INDUSTRIE      1 704,574"),
        ("texte", "09/07  RETRAIT DAB                        200,000"),
        ("texte", "14/07  PRELEVEMENT STEG                    61,300"),
        ("texte", "Nouveau solde : 2 363,424"),
    )]),
    ("ATTESTATION_EMPLOI__scan", True, False, [P(
        ("titre", "ATTESTATION D'EMPLOI"),
        ("texte", "Nous, DELTA INDUSTRIE SA, certifions que M. Karim JLASSI"),
        ("texte", "occupe le poste de technicien supérieur au sein de notre"),
        ("texte", "entreprise depuis le 01/09/2018, en contrat à durée indéterminée."),
        ("texte", "La présente attestation est établie pour servir et valoir"),
        ("texte", "ce que de droit."),
        ("texte", "Monastir, le 03/08/2026 — La Direction"),
    )]),
    ("JUSTIFICATIF_DOMICILE__scan_sonede", True, False, [P(
        ("titre", "SONEDE — FACTURE D'EAU"),
        ("sous", "Société Nationale d'Exploitation et de Distribution des Eaux"),
        ("texte", "Abonné : M. Karim JLASSI"),
        ("texte", "Adresse : 17 rue Ibn Khaldoun, Monastir"),
        ("texte", "Index ancien : 1 486  —  Index nouveau : 1 521"),
        ("texte", "Consommation : 35 m³"),
        ("texte", "Montant à payer : 28,300 DT"),
    )]),
    ("TITRE_SEJOUR__scan", True, False, [P(
        ("titre", "CARTE DE SÉJOUR"),
        ("sous", "République Tunisienne — Ministère de l'Intérieur"),
        ("texte", "Nom : MARTIN"),
        ("texte", "Prénom : Claire"),
        ("texte", "Nationalité : française"),
        ("texte", "Catégorie : résident temporaire"),
        ("texte", "Adresse en Tunisie : Les Berges du Lac, Tunis"),
        ("texte", "Valable jusqu'au : 30/06/2028"),
    )]),
    ("ASSURANCE_VIE__scan", True, False, [P(
        ("titre", "CONDITIONS PARTICULIÈRES — ASSURANCE VIE"),
        ("sous", "Compagnie Fictive d'Assurances"),
        ("texte", "Souscripteur et assuré : M. Karim JLASSI"),
        ("texte", "Garantie : capital décès et invalidité absolue"),
        ("texte", "Capital garanti : 40 000,000 DT"),
        ("texte", "Bénéficiaires : conjoint, à défaut les enfants"),
        ("texte", "Prime annuelle : 480,000 DT"),
        ("texte", "Durée : 10 ans à compter du 01/01/2026"),
    )]),
]


# ── PDF natif (couche texte) ──────────────────────────────────────────────────
def creer_pdf_natif(chemin: Path, pages: list, arabe: bool):
    c = canvas.Canvas(str(chemin), pagesize=A4)
    largeur, hauteur = A4
    for page in pages:
        y = hauteur - 70
        for style, texte in page:
            taille = {"titre": 17, "sous": 11.5, "texte": 10.5}[style]
            c.setFont("DocGras" if style == "titre" else "Doc", taille)
            contenu = ar(texte) if arabe else texte
            if arabe:
                c.drawRightString(largeur - 60, y, contenu)
            elif style == "titre":
                c.drawCentredString(largeur / 2, y, contenu)
            else:
                c.drawString(60, y, contenu)
            y -= taille + (16 if style == "titre" else 9)
        c.showPage()
    c.save()


# ── PDF scanné (image seule, dégradée) ────────────────────────────────────────
def creer_pdf_scanne(chemin: Path, pages: list, arabe: bool):
    dpi = 200
    largeur, hauteur = int(8.27 * dpi), int(11.69 * dpi)
    images = []
    for page in pages:
        img = Image.new("L", (largeur, hauteur), 248)
        dessin = ImageDraw.Draw(img)
        y = 160
        for style, texte in page:
            taille = {"titre": 46, "sous": 31, "texte": 29}[style]
            # Moteur BASIC : la mise en forme de l'arabe est faite par ar(),
            # identique sur toutes les machines (évite une double inversion)
            police = ImageFont.truetype(POLICE_GRAS if style == "titre" else POLICE, taille,
                                        layout_engine=ImageFont.Layout.BASIC)
            contenu = ar(texte) if arabe else texte
            w = dessin.textlength(contenu, font=police)
            if arabe:
                x = largeur - 150 - w
            elif style == "titre":
                x = (largeur - w) / 2
            else:
                x = 150
            dessin.text((x, y), contenu, fill=random.randint(20, 45), font=police)
            y += taille + (45 if style == "titre" else 26)

        # Dégradations de numérisation
        img = img.rotate(random.uniform(-1.2, 1.2), resample=Image.BICUBIC, fillcolor=248)
        img = img.filter(ImageFilter.GaussianBlur(radius=0.7))
        bruit = Image.effect_noise((largeur, hauteur), 18).point(lambda v: v - 128)
        img = Image.eval(Image.blend(img, Image.merge("L", [bruit]).point(lambda v: v + 128), 0.06),
                         lambda v: v)
        images.append(img.convert("RGB"))

    images[0].save(chemin, "PDF", resolution=dpi, save_all=True, append_images=images[1:])


def main():
    SORTIE.mkdir(parents=True, exist_ok=True)
    if not ARABE_OK:
        print("⚠ arabic-reshaper / python-bidi absents : l'arabe sera mal affiché "
              "(pip install arabic-reshaper python-bidi)")
    for nom, scanne, arabe, pages in DOCUMENTS:
        chemin = SORTIE / f"{nom}.pdf"
        (creer_pdf_scanne if scanne else creer_pdf_natif)(chemin, pages, arabe)
        print(f"  ✓ {chemin.name:<45} {'scanné' if scanne else 'natif ':<7} "
              f"{'AR' if arabe else 'FR'}  {len(pages)} page(s)")
    natifs = sum(1 for d in DOCUMENTS if not d[1])
    print(f"\n{len(DOCUMENTS)} documents créés dans {SORTIE}  "
          f"({natifs} natifs, {len(DOCUMENTS) - natifs} scannés)")


if __name__ == "__main__":
    main()