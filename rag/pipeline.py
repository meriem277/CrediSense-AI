# rag/pipeline.py
"""
Pipeline RAG complet : du texte brut au contexte prêt pour le LLM.

Orchestration des 4 étapes :
  1. clean_text()      → texte nettoyé
  2. chunk_document()  → chunks sémantiques
  3. embed_chunks()    → matrice d'embeddings FAISS
  4. store.add()       → index FAISS persisté

Puis au moment d'une question :
  5. retrieve_context() → contexte injecté dans le prompt LLM
"""

from rag.cleaner     import clean_text
from rag.chunker     import chunk_document
from rag.embedder    import embed_chunks
from rag.vector_store import VectorStore
from rag.retriever   import retrieve_context, retrieve_raw


def index_document(
    raw_text: str,
    dossier_id: str,
    store: VectorStore
) -> dict:
    """
    Indexe un document complet dans le store FAISS.
    Appelé après extraction OCR.

    Returns:
        Dictionnaire de statistiques d'indexation.
    """
    print(f"\n[Pipeline RAG] Indexation de '{dossier_id}'")

    # Étape 1 : nettoyage
    clean_result = clean_text(raw_text)
    print(f"[Pipeline RAG] Nettoyage : "
          f"{clean_result.nb_chars_avant} → {clean_result.nb_chars_apres} chars "
          f"({clean_result.taux_reduction}% réduit)")

    # Étape 2 : chunking sémantique
    chunks = chunk_document(clean_result.text, dossier_id)

    if not chunks:
        raise ValueError(
            f"Aucun chunk généré pour '{dossier_id}'. "
            "Vérifiez que le texte OCR n'est pas vide."
        )

    # Étape 3 : embeddings
    embeddings = embed_chunks(chunks)

    # Étape 4 : indexation FAISS
    dossier_index = store.add(dossier_id, chunks, embeddings)

    return {
        "dossier_id":        dossier_id,
        "nb_chunks":         len(chunks),
        "nb_vecteurs":       dossier_index.nb_vecteurs,
        "sections_detectees": dossier_index.sections,
        "taux_compression":  clean_result.taux_reduction,
    }


def query_document(
    question: str,
    dossier_id: str,
    store: VectorStore,
    top_k: int = 5
) -> str:
    """
    Interroge un dossier indexé et retourne le contexte RAG.
    Appelé avant l'inférence LLM.
    """
    return retrieve_context(store, dossier_id, question, top_k)