# rag/embedder.py
"""
Génération des embeddings vectoriels pour les chunks bancaires.

Modèle choisi : paraphrase-multilingual-MiniLM-L12-v2
  - Dimension : 384
  - Langues    : FR, AR, EN (et 48 autres)
  - Taille     : ~120 Mo (téléchargé une seule fois, mis en cache)
  - Licence    : Apache 2.0

Ce modèle est préféré à d'autres (CamemBERT, AraBERT) car :
  1. Il couvre FR et AR simultanément dans le même espace vectoriel
  2. Il est optimisé pour la similarité sémantique (pas la classification)
  3. Sa dimension 384 offre un bon compromis vitesse/précision pour FAISS
"""

import numpy as np
from sentence_transformers import SentenceTransformer
from rag.chunker import Chunk

MODEL_NAME = "paraphrase-multilingual-MiniLM-L12-v2"
EMBED_DIM   = 384

# Singleton : le modèle n'est chargé qu'une seule fois en mémoire
_model: SentenceTransformer | None = None


def get_model() -> SentenceTransformer:
    """Charge et retourne le modèle d'embedding (singleton)."""
    global _model
    if _model is None:
        print(f"[Embedder] Chargement du modèle '{MODEL_NAME}'...")
        _model = SentenceTransformer(MODEL_NAME)
        print(f"[Embedder] Modèle chargé. Dimension : {EMBED_DIM}")
    return _model


def embed_chunks(chunks: list[Chunk]) -> np.ndarray:
    """
    Génère les embeddings pour une liste de chunks.

    Args:
        chunks : liste de Chunk issus du chunker

    Returns:
        Matrice numpy de forme (N, 384), dtype float32, vecteurs normalisés L2.
        Les vecteurs normalisés permettent d'utiliser le produit scalaire
        comme mesure de similarité cosinus dans FAISS (IndexFlatIP).
    """
    if not chunks:
        return np.empty((0, EMBED_DIM), dtype=np.float32)

    model = get_model()
    texts = [c.texte for c in chunks]

    print(f"[Embedder] Embedding de {len(texts)} chunks...")

    embeddings = model.encode(
        texts,
        normalize_embeddings=True,  # L2 normalization → cosine via dot product
        show_progress_bar=len(texts) > 10,
        batch_size=32,
        convert_to_numpy=True,
    )

    result = np.array(embeddings, dtype=np.float32)
    print(f"[Embedder] Matrice générée : {result.shape}")
    return result


def embed_query(question: str) -> np.ndarray:
    """
    Génère l'embedding d'une question posée par l'agent bancaire.

    Returns:
        Vecteur numpy de forme (1, 384), normalisé L2.
        La forme (1, N) est requise par faiss.index.search().
    """
    model = get_model()
    emb = model.encode(
        [question],
        normalize_embeddings=True,
        show_progress_bar=False,
        convert_to_numpy=True,
    )
    return np.array(emb, dtype=np.float32)


def cosine_similarity(v1: np.ndarray, v2: np.ndarray) -> float:
    """
    Calcule la similarité cosinus entre deux vecteurs normalisés.
    Utile pour les tests et la vérification de pertinence.
    """
    # Sur des vecteurs L2-normalisés, cos = produit scalaire
    return float(np.dot(v1.flatten(), v2.flatten()))