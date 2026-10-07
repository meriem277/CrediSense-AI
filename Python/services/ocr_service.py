# services/ocr_service.py
"""
Service OCR CrediSense — PaddleOCR, fusion arabe + français

Fonctionnement :
0. Le format est détecté par les premiers octets du fichier (jamais par son nom
   ni par le type déclaré) : PDF, ou image (JPG, PNG, TIFF, BMP, WebP).
   Les photos (CIN, fiche de paie prises au téléphone) vont directement à l'étape 2.
   Tout autre format (Word .docx, HEIC…) est refusé avec un message explicite.
1. PDF natif (texte sélectionnable)   → extraction directe avec PyMuPDF
2. PDF scanné ou image (CIN, attestation, …) → OCR PaddleOCR en FUSION :
      a. détection des zones de texte (PP-OCRv5_mobile_det)          — 1 seule fois
      b. lecture de chaque zone par le modèle ARABE
         (arabic_PP-OCRv5_mobile_rec)
      c. lecture de la MÊME zone par le modèle LATIN
         (latin_PP-OCRv5_mobile_rec)
      d. fusion zone par zone :
           - zone sans arabe (français, chiffres) → lecture latine
           - zone arabe                          → mots arabes de la lecture arabe
           - zone mixte arabe + français/chiffres → mots arabes + lecture latine

Pourquoi la fusion (benchmark sur une attestation bilingue, 181 mots) :
    - PaddleOCR arabe seul   : lit 85 % de l'arabe mais perd le français et les
                               chiffres des lignes mixtes (2 nombres sur 10)
    - PaddleOCR français seul : lit le français et les chiffres, ignore l'arabe
    - Les deux sont complémentaires, et utilisent le même framework (pas de conflit)

Post-traitement :
    - zones à faible confiance écartées (motifs de sécurité lus comme du texte)
    - zones regroupées par ligne (position verticale), ordonnées de droite à
      gauche pour les lignes arabes, de gauche à droite sinon
    - normalisation Unicode NFKC (formes contextuelles arabes → lettres standard)
"""

import os

# Ne pas tester la connexion aux serveurs de modèles à chaque démarrage
os.environ.setdefault("DISABLE_MODEL_SOURCE_CHECK", "True")

import hashlib
import logging
import statistics
import threading
import time
import unicodedata
from pathlib import Path
from typing import Optional

import numpy as np

try:
    import pymupdf as fitz  # nouveau nom officiel de PyMuPDF
except ImportError:
    import fitz

logger = logging.getLogger(__name__)

SEUIL_TEXTE_NATIF     = 50     # chars minimum pour considérer un PDF comme natif
CACHE_SIZE            = 128    # nombre de résultats gardés en cache
DPI                   = 300    # résolution de conversion PDF scanné → image
MAX_COTE_IMAGE        = 4000   # px : une photo plus grande est réduite (A4 à 300 dpi ≈ 3500 px)

MESSAGE_FORMAT_NON_SUPPORTE = (
    "Format de fichier non supporté. Formats acceptés : PDF, JPG, PNG "
    "(TIFF, BMP et WebP aussi). Un document Word (.docx) doit d'abord être "
    "converti en PDF ; une photo HEIC doit être enregistrée en JPG."
)
SEUIL_CONFIANCE_MIN   = 0.50   # zones en dessous : considérées comme du bruit
SEUIL_LECTURE_LATINE  = 0.80   # zone arabe : la lecture latine n'est ajoutée que
                               # si le modèle latin est sûr de lui (sinon : bruit)
TOLERANCE_LIGNE       = 0.5    # 2 zones sont sur la même ligne si leurs centres
                               # sont à moins de 0.5 × hauteur médiane
TAILLE_LOT_LATIN      = 8      # zones lues en même temps par le modèle latin

MODELE_DETECTION      = "PP-OCRv5_mobile_det"
MODELE_REC_ARABE      = "arabic_PP-OCRv5_mobile_rec"
MODELE_REC_LATIN      = "latin_PP-OCRv5_mobile_rec"

# True  : fusion arabe + latin (recommandé pour les documents bilingues)
# False : modèle arabe seul (plus rapide, mais perd le français des lignes mixtes)
FUSION_ACTIVE         = True

# oneDNN (MKLDNN) accélère l'inférence sur CPU, mais provoque l'erreur
# "ConvertPirAttribute2RuntimeAttribute not support" avec certaines versions
# de paddlepaddle 3.x. À passer à True si la version installée le supporte.
ACTIVER_MKLDNN        = False

# Plages Unicode de l'alphabet arabe (lettres, formes de présentation)
_PLAGES_ARABES = (
    (0x0600, 0x06FF), (0x0750, 0x077F), (0x08A0, 0x08FF),
    (0xFB50, 0xFDFF), (0xFE70, 0xFEFF),
)


# ═════════════════════════════════════════════════════════════════════════
# Fonctions utilitaires (sans état)
# ═════════════════════════════════════════════════════════════════════════

def _est_lettre_arabe(c: str) -> bool:
    code = ord(c)
    return any(debut <= code <= fin for debut, fin in _PLAGES_ARABES) and c.isalpha()


def contient_arabe(texte: str) -> bool:
    return any(_est_lettre_arabe(c) for c in texte)


def est_arabe(texte: str) -> bool:
    """Vrai si le texte contient plus de lettres arabes que de lettres latines."""
    arabes = sum(_est_lettre_arabe(c) for c in texte)
    latines = sum(c.isalpha() and not _est_lettre_arabe(c) for c in texte)
    return arabes > latines


def detecter_format(donnees: bytes) -> str:
    """
    Reconnaît le format réel d'un fichier à ses premiers octets.
    Renvoie "pdf", "image", "zip" (docx, xlsx… sont des zip) ou "inconnu".
    """
    if b"%PDF" in donnees[:1024]:
        return "pdf"
    if (donnees.startswith(b"\x89PNG\r\n\x1a\n")            # PNG
            or donnees.startswith(b"\xff\xd8\xff")            # JPEG
            or donnees.startswith((b"II*\x00", b"MM\x00*"))   # TIFF
            or donnees.startswith(b"BM")                      # BMP
            or (donnees[:4] == b"RIFF" and donnees[8:12] == b"WEBP")):
        return "image"
    if donnees.startswith(b"PK\x03\x04"):
        return "zip"
    return "inconnu"


def limiter_taille(image: np.ndarray, max_cote: int = MAX_COTE_IMAGE) -> np.ndarray:
    """Réduit une image dont le plus grand côté dépasse `max_cote` px (sinon inchangée)."""
    import cv2

    hauteur, largeur = image.shape[:2]
    plus_grand = max(hauteur, largeur)
    if plus_grand <= max_cote:
        return image
    facteur = max_cote / plus_grand
    return cv2.resize(image, (int(largeur * facteur), int(hauteur * facteur)),
                      interpolation=cv2.INTER_AREA)


def normaliser(texte: str) -> str:
    """NFKC : convertit les formes contextuelles arabes en lettres standard."""
    return unicodedata.normalize("NFKC", str(texte)).strip()


def fusionner_lectures(texte_ar: str, score_ar: float,
                       texte_lat: str, score_lat: float) -> tuple[str, float]:
    """
    Combine les lectures arabe et latine d'UNE même zone de texte.

    - Zone sans arabe (français, chiffres) → lecture latine (le spécialiste)
    - Zone arabe → mots arabes de la lecture arabe, complétés par la lecture
      latine si celle-ci est fiable (zone mixte : "مريم رحومة / Meriem Rehouma",
      ou date "27 جويلية 2001" dont le modèle arabe perd les chiffres)
    """
    texte_ar, texte_lat = normaliser(texte_ar), normaliser(texte_lat)
    mots_arabes = [m for m in texte_ar.split() if contient_arabe(m)]

    if not mots_arabes:
        if texte_lat:
            return texte_lat, score_lat
        return texte_ar, score_ar

    texte = " ".join(mots_arabes)
    lecture_latine_fiable = (
        score_lat >= SEUIL_LECTURE_LATINE
        and sum(c.isalnum() for c in texte_lat) >= 2
    )
    if lecture_latine_fiable:
        texte = f"{texte} {texte_lat}"
    return texte, score_ar


def rogner_zone(image: np.ndarray, poly) -> np.ndarray:
    """
    Découpe une zone de texte (polygone à 4 points) et la redresse,
    comme le fait PaddleOCR avant la reconnaissance.
    """
    import cv2

    pts = np.asarray(poly, dtype=np.float32).reshape(-1, 2)
    if len(pts) != 4:  # polygone quelconque → rectangle englobant
        x1, y1 = pts.min(axis=0)
        x2, y2 = pts.max(axis=0)
        pts = np.float32([[x1, y1], [x2, y1], [x2, y2], [x1, y2]])

    largeur = int(max(np.linalg.norm(pts[0] - pts[1]), np.linalg.norm(pts[2] - pts[3])))
    hauteur = int(max(np.linalg.norm(pts[0] - pts[3]), np.linalg.norm(pts[1] - pts[2])))
    largeur, hauteur = max(largeur, 1), max(hauteur, 1)

    cible = np.float32([[0, 0], [largeur, 0], [largeur, hauteur], [0, hauteur]])
    matrice = cv2.getPerspectiveTransform(pts, cible)
    zone = cv2.warpPerspective(image, matrice, (largeur, hauteur),
                               borderMode=cv2.BORDER_REPLICATE, flags=cv2.INTER_CUBIC)
    if hauteur / largeur >= 1.5:  # texte vertical → on le couche
        zone = np.ascontiguousarray(np.rot90(zone))
    return zone


def boite_englobante(poly) -> tuple[float, float, float, float]:
    pts = np.asarray(poly, dtype=float).reshape(-1, 2)
    return pts[:, 0].min(), pts[:, 1].min(), pts[:, 0].max(), pts[:, 1].max()


def regrouper_par_ligne(zones: list[dict]) -> list[str]:
    """
    Regroupe les zones qui sont à la même hauteur, puis les ordonne :
    de droite à gauche si la ligne est en arabe, de gauche à droite sinon.
    Ex. CIN : "رحومة" et "اللقب" (deux zones) → "اللقب رحومة".
    """
    if not zones:
        return []

    hauteur_med = statistics.median(z["y2"] - z["y1"] for z in zones) or 1.0
    centre = lambda z: (z["y1"] + z["y2"]) / 2

    groupes: list[list[dict]] = []
    for z in sorted(zones, key=centre):
        if groupes:
            centre_groupe = sum(centre(g) for g in groupes[-1]) / len(groupes[-1])
            if abs(centre(z) - centre_groupe) <= hauteur_med * TOLERANCE_LIGNE:
                groupes[-1].append(z)
                continue
        groupes.append([z])

    lignes = []
    for groupe in groupes:
        rtl = est_arabe(" ".join(z["texte"] for z in groupe))
        ordonnees = sorted(groupe, key=lambda z: z["x1"], reverse=rtl)
        lignes.append(" ".join(z["texte"] for z in ordonnees))
    return lignes


# ═════════════════════════════════════════════════════════════════════════
# Service
# ═════════════════════════════════════════════════════════════════════════

class OcrService:

    def __init__(self):
        self._ocr = None          # pipeline : détection + lecture arabe
        self._rec_latin = None    # lecture latine (fusion)
        self._verrou = threading.Lock()   # PaddleOCR n'est pas thread-safe
        self._cache: dict = {}
        self._charger_modeles()

    # ── Chargement au démarrage ───────────────────────────────────────────────

    def _charger_modeles(self):
        """Charge les modèles PaddleOCR une seule fois au démarrage du service."""
        try:
            from paddleocr import PaddleOCR
        except ImportError:
            logger.warning("PaddleOCR non disponible — OCR des PDF scannés désactivé")
            return

        try:
            t0 = time.time()
            self._ocr = PaddleOCR(
                text_detection_model_name=MODELE_DETECTION,
                text_recognition_model_name=MODELE_REC_ARABE,
                use_doc_orientation_classify=False,  # documents déjà droits
                use_doc_unwarping=False,             # pas de redressement de page
                use_textline_orientation=False,
                enable_mkldnn=ACTIVER_MKLDNN,
            )
            logger.info("PaddleOCR prêt en %.2fs (%s + %s)",
                        time.time() - t0, MODELE_DETECTION, MODELE_REC_ARABE)
        except Exception:
            logger.exception("Échec du chargement de PaddleOCR — OCR des PDF scannés désactivé")
            self._ocr = None
            return

        if FUSION_ACTIVE:
            try:
                from paddleocr import TextRecognition
                t0 = time.time()
                try:
                    self._rec_latin = TextRecognition(model_name=MODELE_REC_LATIN,
                                                      enable_mkldnn=ACTIVER_MKLDNN)
                except TypeError:  # version sans le paramètre enable_mkldnn
                    self._rec_latin = TextRecognition(model_name=MODELE_REC_LATIN)
                logger.info("Lecture latine prête en %.2fs (%s) — fusion activée",
                            time.time() - t0, MODELE_REC_LATIN)
            except Exception:
                logger.exception("Modèle latin non chargé — fusion désactivée, arabe seul")
                self._rec_latin = None

    # ── Point d'entrée principal ──────────────────────────────────────────────

    def extraire(self, pdf_path: str, type_original: str = "pdf",
                 langue: Optional[str] = None) -> dict:
        """
        Extrait le texte d'un PDF.

        `langue` est conservé pour compatibilité avec les anciens appels,
        mais n'est plus utilisé : la fusion lit l'arabe et le français.
        `type_original` n'est plus utilisé non plus : le format est détecté
        à partir du contenu du fichier (PDF ou image).
        """
        t0   = time.time()
        path = Path(pdf_path)

        if not path.exists():
            return self._erreur(f"Fichier introuvable : {pdf_path}")

        donnees = path.read_bytes()
        format_ = detecter_format(donnees)
        if format_ not in ("pdf", "image"):
            logger.warning("Format refusé — %s (%s, type déclaré=%s)",
                           path.name, format_, type_original)
            return self._erreur(MESSAGE_FORMAT_NON_SUPPORTE)

        # ── Cache : empreinte SHA-256 du fichier ─────────────────────────────
        cle    = hashlib.sha256(donnees).hexdigest()
        cached = self._cache.get(cle)
        if cached:
            logger.info("Cache HIT — %s (%.0fms)", path.name, (time.time() - t0) * 1000)
            return cached

        try:
            if format_ == "pdf":
                result = self._detection_auto(donnees)
            else:
                result = self._ocr_image(donnees)

            result["duree_ms"] = round((time.time() - t0) * 1000, 1)
            logger.info("OCR terminé — cas=%s, moteur=%s, %d chars, %.0fms",
                        result["cas"], result["moteur"], len(result["texte"]),
                        result["duree_ms"])

            self._cache_set(cle, result)
            return result

        except Exception as e:
            logger.error("Erreur extraction : %s", str(e), exc_info=True)
            return self._erreur(str(e))

    # ── Détection natif / scanné ─────────────────────────────────────────────

    def _detection_auto(self, pdf_bytes: bytes) -> dict:
        """Essaie PyMuPDF ; si le texte est insuffisant, c'est un scan → PaddleOCR."""
        result = self._extraire_natif(pdf_bytes)

        if len(result["texte"].strip()) < SEUIL_TEXTE_NATIF:
            logger.info("Texte court (%d chars) → PDF scanné, OCR PaddleOCR",
                        len(result["texte"].strip()))
            return self._ocr_pdf(pdf_bytes)

        return result

    # ── PDF natif : PyMuPDF ──────────────────────────────────────────────────

    def _extraire_natif(self, pdf_bytes: bytes) -> dict:
        """Extraction du texte intégré au PDF (quelques millisecondes)."""
        with fitz.open(stream=pdf_bytes, filetype="pdf") as doc:
            nb_pages = len(doc)
            texte    = "\n".join(page.get_text() for page in doc)

        logger.info("PyMuPDF — %d pages, %d chars", nb_pages, len(texte))
        return {
            "texte":          texte,
            "nb_pages":       nb_pages,
            "confidence":     None,
            "cas":            "PDF_NATIF",
            "moteur":         "pymupdf",
            "langue_detectee": None,
            "zones_ecartees": 0,
            "statut":         "SUCCESS",
        }

    # ── PDF scanné : PaddleOCR ───────────────────────────────────────────────

    @staticmethod
    def _pages_en_images(pdf_bytes: bytes) -> list:
        """Convertit chaque page en image BGR (format attendu par PaddleOCR), en mémoire."""
        images = []
        with fitz.open(stream=pdf_bytes, filetype="pdf") as doc:
            for page in doc:
                pix = page.get_pixmap(dpi=DPI, colorspace=fitz.csRGB, alpha=False)
                rgb = np.frombuffer(pix.samples, dtype=np.uint8).reshape(pix.h, pix.w, 3)
                images.append(np.ascontiguousarray(rgb[:, :, ::-1]))  # RGB → BGR
        return images

    def _ocr_pdf(self, pdf_bytes: bytes) -> dict:
        if self._ocr is None:
            raise RuntimeError(
                "PaddleOCR non disponible — vérifiez l'installation (paddlepaddle, paddleocr)"
            )
        return self._ocr_images(self._pages_en_images(pdf_bytes), cas="PDF_SCAN")

    def _ocr_image(self, donnees: bytes) -> dict:
        """Photo ou scan image (JPG, PNG…) : décodée directement, sans passer par un PDF."""
        import cv2

        if self._ocr is None:
            raise RuntimeError(
                "PaddleOCR non disponible — vérifiez l'installation (paddlepaddle, paddleocr)"
            )
        # IMREAD_COLOR : BGR (format attendu par PaddleOCR), orientation EXIF appliquée
        image = cv2.imdecode(np.frombuffer(donnees, dtype=np.uint8), cv2.IMREAD_COLOR)
        if image is None:
            raise ValueError("Image illisible ou corrompue")
        return self._ocr_images([limiter_taille(image)], cas="IMAGE")

    def _ocr_images(self, images: list, cas: str) -> dict:
        """OCR PaddleOCR (fusion arabe + latin) d'une liste d'images BGR, une par page."""
        fusion = self._rec_latin is not None

        lignes, confidences, nb_ecartees = [], [], 0
        for image in images:
            with self._verrou:
                zones, ecartees = self._lire_page(image, fusion)
            nb_ecartees += ecartees
            confidences.extend(z["score"] for z in zones)
            lignes.extend(regrouper_par_ligne(zones))
            lignes.append("")  # ligne vide entre les pages

        texte      = "\n".join(lignes).strip()
        confidence = sum(confidences) / len(confidences) if confidences else 0.0
        moteur     = "paddleocr-fusion" if fusion else "paddleocr-arabe"

        logger.info("%s — %d pages, conf=%.3f, %d chars, %d zones écartées",
                    moteur, len(images), confidence, len(texte), nb_ecartees)

        return {
            "texte":          texte,
            "nb_pages":       len(images),
            "confidence":     round(confidence, 4),
            "cas":            cas,
            "moteur":         moteur,
            "langue_detectee": ("ar" if est_arabe(texte) else "fr") if texte else None,
            "zones_ecartees": nb_ecartees,
            "statut":         "SUCCESS",
        }

    def _lire_page(self, image: np.ndarray, fusion: bool) -> tuple[list[dict], int]:
        """
        Détection + lecture arabe (pipeline), puis lecture latine des mêmes zones,
        puis fusion zone par zone. Renvoie (zones retenues, nombre de zones écartées).
        """
        zones, ecartees = [], 0

        for res in self._ocr.predict(image):
            polys     = list(res["rec_polys"])
            textes_ar = list(res["rec_texts"])
            scores_ar = [float(s) for s in res["rec_scores"]]

            if fusion and polys:
                rognures = [rogner_zone(image, p) for p in polys]
                lectures = list(self._rec_latin.predict(rognures, batch_size=TAILLE_LOT_LATIN))
                textes_lat = [str(r["rec_text"]) for r in lectures]
                scores_lat = [float(r["rec_score"]) for r in lectures]
            else:
                textes_lat = [""] * len(polys)
                scores_lat = [0.0] * len(polys)

            for poly, t_ar, s_ar, t_lat, s_lat in zip(polys, textes_ar, scores_ar,
                                                       textes_lat, scores_lat):
                texte, score = fusionner_lectures(t_ar, s_ar, t_lat, s_lat)
                if not texte or score < SEUIL_CONFIANCE_MIN:
                    ecartees += 1
                    continue
                x1, y1, x2, y2 = boite_englobante(poly)
                zones.append({"texte": texte, "score": score,
                              "x1": x1, "y1": y1, "x2": x2, "y2": y2})

        return zones, ecartees

    # ── Cache FIFO ───────────────────────────────────────────────────────────

    def _cache_set(self, key: str, value: dict):
        if len(self._cache) >= CACHE_SIZE:
            oldest = next(iter(self._cache))
            del self._cache[oldest]
        self._cache[key] = value

    def _vider_cache(self):
        self._cache.clear()
        logger.info("Cache OCR vidé")

    # ── Helpers ───────────────────────────────────────────────────────────────

    def _erreur(self, message: str) -> dict:
        return {
            "texte":          "",
            "nb_pages":       0,
            "confidence":     None,
            "cas":            "ERREUR",
            "moteur":         None,
            "langue_detectee": None,
            "zones_ecartees": 0,
            "statut":         "FAILURE",
            "erreur":         message,
            "duree_ms":       0,
        }

    def stats(self) -> dict:
        """Retourne les statistiques du service."""
        return {
            "cache_size":  len(self._cache),
            "cache_max":   CACHE_SIZE,
            "ocr_charge":  self._ocr is not None,
            "fusion":      self._rec_latin is not None,
            "modele_det":  MODELE_DETECTION,
            "modele_rec":  [MODELE_REC_ARABE] + ([MODELE_REC_LATIN] if self._rec_latin else []),
            "mkldnn":      ACTIVER_MKLDNN,
        }