# services/ocr_service.py
"""
Service OCR CrediSense — version optimisée
- Doctr chargé au démarrage (pas de latence premier appel)
- Lecture depuis bytes (pas de fichier temporaire)
- Traitement parallèle des pages avec ThreadPoolExecutor
- Cache LRU sur le hash du fichier
- Logging structuré avec métriques de performance
"""

# ── Stub weasyprint ──────────────────────────────────────────────────────
# doctr importe weasyprint en interne (pour une fonctionnalité HTML qu'on
# n'utilise pas). weasyprint nécessite GTK3 (librairies système Windows)
# qui ne sont pas installées. On simule un faux module pour éviter le crash.
import sys
import types

if "weasyprint" not in sys.modules:
    _fake_weasyprint = types.ModuleType("weasyprint")
    _fake_weasyprint.HTML = None
    sys.modules["weasyprint"] = _fake_weasyprint
# ──────────────────────────────────────────────────────────────────────────

import logging
import time
import hashlib
import fitz  # PyMuPDF
from pathlib import Path
from functools import lru_cache
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import Optional

logger = logging.getLogger(__name__)
SEUIL_TEXTE_NATIF  = 50    # chars minimum → PDF natif
CACHE_SIZE         = 128   # nombre de résultats en cache
MAX_WORKERS        = 4     # threads parallèles pour les pages


class OcrService:

    def __init__(self):
        self._doctr_model = None
        self._executor    = ThreadPoolExecutor(max_workers=MAX_WORKERS)
        self._charger_doctr()

    # ── Chargement au démarrage ───────────────────────────────────────────────

    def _charger_doctr(self):
        """Charge Doctr une seule fois au démarrage du service."""
        try:
            logger.info("Chargement Doctr OCR au démarrage...")
            t0 = time.time()
            from doctr.models import ocr_predictor
            self._doctr_model = ocr_predictor(pretrained=True)
            logger.info("Doctr prêt en %.2fs", time.time() - t0)
        except ImportError:
            logger.warning("Doctr non disponible — OCR images désactivé")
            self._doctr_model = None

    # ── Point d'entrée principal ──────────────────────────────────────────────

    def extraire(self, pdf_path: str, type_original: str) -> dict:
        """
        Extrait le texte d'un fichier selon son type.
        Utilise le cache si le fichier a déjà été traité.
        """
        t0   = time.time()
        path = Path(pdf_path)

        if not path.exists():
            return self._erreur(f"Fichier introuvable : {pdf_path}")

        # ── Cache : calculer le hash du fichier ─────────────────────────────
        file_hash = self._hash_fichier(path)
        cached    = self._cache_get(file_hash)
        if cached:
            logger.info("Cache HIT — %s (%.0fms)", path.name, (time.time()-t0)*1000)
            return cached

        # ── Extraction selon le type ─────────────────────────────────────────
        ext = type_original.lower().strip()
        try:
            if ext == "docx":
                result = self._extraire_natif(path, cas="CAS1_DOCX")
            elif ext in ("jpg", "jpeg", "png"):
                result = self._extraire_doctr_bytes(path.read_bytes(), cas="CAS2_IMAGE")
            elif ext == "pdf":
                result = self._detection_auto(path)
            else:
                return self._erreur(f"Type non supporté : {ext}")

            result["duree_ms"] = round((time.time() - t0) * 1000, 1)
            logger.info("OCR terminé — cas=%s, %d chars, %.0fms",
                       result["cas"], len(result["texte"]), result["duree_ms"])

            # Mettre en cache
            self._cache_set(file_hash, result)
            return result

        except Exception as e:
            logger.error("Erreur extraction : %s", str(e), exc_info=True)
            return self._erreur(str(e))

    # ── CAS 1 : PyMuPDF natif ────────────────────────────────────────────────

    def _extraire_natif(self, path: Path, cas: str) -> dict:
        """Extraction texte PDF natif avec PyMuPDF — parallèle par page."""
        doc = fitz.open(str(path))
        nb_pages = len(doc)

        def extraire_page(page_num: int) -> str:
            return doc[page_num].get_text()

        # Traitement parallèle des pages
        textes   = [""] * nb_pages
        futures  = {
            self._executor.submit(extraire_page, i): i
            for i in range(nb_pages)
        }
        for future in as_completed(futures):
            idx         = futures[future]
            textes[idx] = future.result()

        doc.close()
        texte = "\n".join(textes)

        logger.info("PyMuPDF — %d pages, %d chars", nb_pages, len(texte))
        return {
            "texte":      texte,
            "nb_pages":   nb_pages,
            "confidence": None,
            "cas":        cas,
            "statut":     "SUCCESS"
        }

    # ── CAS 2 : Doctr OCR ────────────────────────────────────────────────────

    def _extraire_doctr_bytes(self, pdf_bytes: bytes, cas: str) -> dict:
        """OCR Doctr depuis bytes — pas de fichier temporaire."""
        if self._doctr_model is None:
            raise RuntimeError("Doctr non disponible — installez python-doctr[torch]")

        from doctr.io import DocumentFile

        # Lire depuis bytes directement
        doc    = DocumentFile.from_pdf(pdf_bytes)
        result = self._doctr_model(doc)

        # Extraction optimisée avec compréhension de liste
        mots = [
            word
            for page  in result.pages
            for block in page.blocks
            for line  in block.lines
            for word  in line.words
        ]

        texte      = " ".join(w.value for w in mots)
        confidence = (
            sum(w.confidence for w in mots) / len(mots)
            if mots else 0.0
        )

        logger.info("Doctr — %d pages, conf=%.3f, %d chars",
                   len(result.pages), confidence, len(texte))

        return {
            "texte":      texte,
            "nb_pages":   len(result.pages),
            "confidence": round(confidence, 4),
            "cas":        cas,
            "statut":     "SUCCESS"
        }

    # ── CAS 3 : Détection automatique ────────────────────────────────────────

    def _detection_auto(self, path: Path) -> dict:
        """Essaie PyMuPDF, bascule sur Doctr si texte insuffisant."""
        result = self._extraire_natif(path, cas="CAS3_NATIF")

        if len(result["texte"].strip()) < SEUIL_TEXTE_NATIF:
            logger.info(
                "Texte court (%d chars) → Doctr OCR",
                len(result["texte"].strip())
            )
            return self._extraire_doctr_bytes(
                path.read_bytes(), cas="CAS3_SCAN"
            )

        return result

    # ── Cache LRU ────────────────────────────────────────────────────────────

    _cache: dict = {}

    def _hash_fichier(self, path: Path) -> str:
        h = hashlib.md5()
        h.update(path.read_bytes())
        return h.hexdigest()

    def _cache_get(self, key: str) -> Optional[dict]:
        return self._cache.get(key)

    def _cache_set(self, key: str, value: dict):
        if len(self._cache) >= CACHE_SIZE:
            # Supprimer la première entrée (FIFO simple)
            oldest = next(iter(self._cache))
            del self._cache[oldest]
        self._cache[key] = value

    def _vider_cache(self):
        self._cache.clear()
        logger.info("Cache OCR vidé")

    # ── Helpers ───────────────────────────────────────────────────────────────

    def _erreur(self, message: str) -> dict:
        return {
            "texte":      "",
            "nb_pages":   0,
            "confidence": None,
            "cas":        "ERREUR",
            "statut":     "FAILURE",
            "erreur":     message,
            "duree_ms":   0
        }

    def stats(self) -> dict:
        """Retourne les statistiques du service."""
        return {
            "cache_size":   len(self._cache),
            "cache_max":    CACHE_SIZE,
            "doctr_loaded": self._doctr_model is not None,
            "max_workers":  MAX_WORKERS
        }