# rag/vector_store.py
"""
Store vectoriel FAISS avec persistance disque.

Architecture :
  - Un index FAISS indépendant par dossier bancaire
  - Persistance via faiss.write_index() / faiss.read_index()
  - Métadonnées (chunks) sérialisées séparément via pickle
  - Chargement automatique de tous les index au démarrage

Type d'index : IndexFlatIP (Inner Product)
  Sur des vecteurs L2-normalisés, IP = similarité cosinus.
  IndexFlatIP est l'index de référence : exact, pas d'approximation,
  idéal pour des volumes < 100 000 vecteurs (contexte PFE).
"""

import faiss
import numpy as np
import pickle
import os
from pathlib import Path
from dataclasses import dataclass
from rag.chunker import Chunk

FAISS_DIR = Path("data/faiss_indexes")
EMBED_DIM = 384


@dataclass
class DossierIndex:
    dossier_id: str
    index: faiss.IndexFlatIP
    chunks: list[Chunk]

    @property
    def nb_vecteurs(self) -> int:
        return self.index.ntotal

    @property
    def sections(self) -> list[str]:
        return list({c.section for c in self.chunks})


class VectorStore:
    """
    Gestionnaire de tous les index FAISS.
    Un index = un dossier bancaire.
    """

    def __init__(self):
        FAISS_DIR.mkdir(parents=True, exist_ok=True)
        # { dossier_id: DossierIndex }
        self._store: dict[str, DossierIndex] = {}
        self._load_all_from_disk()
        print(f"[VectorStore] {len(self._store)} dossier(s) chargé(s) depuis le disque.")

    # ── Écriture ─────────────────────────────────────────────────────────────

    def add(
        self,
        dossier_id: str,
        chunks: list[Chunk],
        embeddings: np.ndarray
    ) -> DossierIndex:
        """
        Crée (ou remplace) l'index FAISS pour un dossier.

        Args:
            dossier_id  : identifiant unique du dossier
            chunks      : liste des chunks du document
            embeddings  : matrice (N, 384) float32 normalisée

        Returns:
            DossierIndex créé et persisté sur disque.
        """
        if embeddings.shape[0] != len(chunks):
            raise ValueError(
                f"Incohérence : {embeddings.shape[0]} embeddings "
                f"pour {len(chunks)} chunks."
            )

        # Création de l'index FAISS
        index = faiss.IndexFlatIP(EMBED_DIM)
        index.add(embeddings)

        dossier_index = DossierIndex(
            dossier_id=dossier_id,
            index=index,
            chunks=chunks
        )
        self._store[dossier_id] = dossier_index
        self._save_to_disk(dossier_id)

        print(f"[VectorStore] '{dossier_id}' indexé : "
              f"{index.ntotal} vecteurs, "
              f"sections : {dossier_index.sections}")

        return dossier_index

    # ── Lecture ──────────────────────────────────────────────────────────────

    def search(
        self,
        dossier_id: str,
        query_embedding: np.ndarray,
        top_k: int = 5
    ) -> list[tuple[Chunk, float]]:
        """
        Recherche les top_k chunks les plus similaires à la requête.

        Args:
            dossier_id      : dossier à interroger
            query_embedding : vecteur (1, 384) de la question
            top_k           : nombre de résultats à retourner

        Returns:
            Liste de (Chunk, score_similarité) triée par score décroissant.
        """
        if not self.exists(dossier_id):
            raise KeyError(f"Dossier '{dossier_id}' non trouvé dans le store.")

        di = self._store[dossier_id]
        actual_k = min(top_k, di.nb_vecteurs)

        distances, indices = di.index.search(query_embedding, actual_k)

        results = []
        for score, idx in zip(distances[0], indices[0]):
            if idx >= 0:  # FAISS retourne -1 si pas assez de vecteurs
                results.append((di.chunks[idx], float(score)))

        return results

    def search_by_section(
        self,
        dossier_id: str,
        section: str
    ) -> list[Chunk]:
        """
        Retourne tous les chunks d'une section donnée (sans embedding).
        Utile pour extraire directement tous les revenus, par exemple.
        """
        if not self.exists(dossier_id):
            return []
        return [
            c for c in self._store[dossier_id].chunks
            if c.section == section
        ]

    def exists(self, dossier_id: str) -> bool:
        return dossier_id in self._store

    def info(self, dossier_id: str) -> dict:
        if not self.exists(dossier_id):
            raise KeyError(f"Dossier '{dossier_id}' non trouvé.")
        di = self._store[dossier_id]
        return {
            "dossier_id":  di.dossier_id,
            "nb_chunks":   len(di.chunks),
            "nb_vecteurs": di.nb_vecteurs,
            "sections":    di.sections,
        }

    def list_dossiers(self) -> list[str]:
        return list(self._store.keys())

    def delete(self, dossier_id: str):
        """Supprime un dossier de la mémoire et du disque."""
        self._store.pop(dossier_id, None)
        (FAISS_DIR / f"{dossier_id}.faiss").unlink(missing_ok=True)
        (FAISS_DIR / f"{dossier_id}.pkl").unlink(missing_ok=True)
        print(f"[VectorStore] '{dossier_id}' supprimé.")

    # ── Persistance ──────────────────────────────────────────────────────────

    def _save_to_disk(self, dossier_id: str):
        """Sauvegarde l'index FAISS et les chunks sur disque."""
        di = self._store[dossier_id]
        faiss.write_index(di.index, str(FAISS_DIR / f"{dossier_id}.faiss"))
        with open(FAISS_DIR / f"{dossier_id}.pkl", "wb") as f:
            pickle.dump(di.chunks, f)

    def _load_all_from_disk(self):
        """Charge tous les index FAISS existants au démarrage."""
        for faiss_file in FAISS_DIR.glob("*.faiss"):
            dossier_id = faiss_file.stem
            pkl_file = FAISS_DIR / f"{dossier_id}.pkl"
            if not pkl_file.exists():
                continue
            try:
                index = faiss.read_index(str(faiss_file))
                with open(pkl_file, "rb") as f:
                    chunks = pickle.load(f)
                self._store[dossier_id] = DossierIndex(
                    dossier_id=dossier_id,
                    index=index,
                    chunks=chunks
                )
            except Exception as e:
                print(f"[VectorStore] Erreur chargement '{dossier_id}' : {e}")