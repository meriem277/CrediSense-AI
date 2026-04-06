# rag/retriever.py
"""
Retriever sémantique : transforme une question en contexte RAG.

Pipeline :
  question (str)
    → embed_query()           → vecteur (1, 384)
    → vector_store.search()   → top-k (Chunk, score)
    → format_context()        → texte structuré pour le LLM
"""

from rag.embedder import embed_query
from rag.vector_store import VectorStore
from rag.chunker import Chunk

# Score minimal pour qu'un chunk soit considéré pertinent
# (similarité cosinus sur vecteurs normalisés, ∈ [-1, 1])
MIN_RELEVANCE_SCORE = 0.25


def retrieve_context(
    store: VectorStore,
    dossier_id: str,
    question: str,
    top_k: int = 5
) -> str:
    """
    Récupère le contexte pertinent pour une question donnée.

    Args:
        store      : instance VectorStore partagée
        dossier_id : dossier à interroger
        question   : question posée par l'agent bancaire
        top_k      : nombre max de chunks à récupérer

    Returns:
        Texte de contexte structuré, prêt à être injecté dans le prompt LLM.
        Format : blocs étiquetés par section et score, séparés par ---
    """
    # 1. Embedding de la question
    query_emb = embed_query(question)

    # 2. Recherche FAISS
    results = store.search(dossier_id, query_emb, top_k=top_k)

    # 3. Filtrage par score minimal de pertinence
    relevant = [
        (chunk, score)
        for chunk, score in results
        if score >= MIN_RELEVANCE_SCORE
    ]

    if not relevant:
        return (
            "Aucune information suffisamment pertinente n'a été trouvée "
            "dans le dossier pour répondre à cette question."
        )

    # 4. Formatage du contexte pour le LLM
    return _format_context(relevant, question)


def retrieve_raw(
    store: VectorStore,
    dossier_id: str,
    question: str,
    top_k: int = 5
) -> list[tuple[Chunk, float]]:
    """
    Variante qui retourne les (Chunk, score) bruts sans formatage.
    Utile pour les tests, le debug et les métriques de qualité RAG.
    """
    query_emb = embed_query(question)
    return store.search(dossier_id, query_emb, top_k=top_k)


def _format_context(
    results: list[tuple[Chunk, float]],
    question: str
) -> str:
    """
    Formate les chunks récupérés en un bloc de contexte lisible par le LLM.

    Format choisi :
      [Section: revenus | Pertinence: 0.87]
      <texte du chunk>
      ---
    """
    parts = []
    for chunk, score in results:
        header = (
            f"[Section: {chunk.section} | "
            f"Pertinence: {score:.2f} | "
            f"Chunk: {chunk.id}]"
        )
        parts.append(f"{header}\n{chunk.texte}")

    context = "\n\n---\n\n".join(parts)

    # Log utile pour le debug
    print(f"[Retriever] Question : '{question[:60]}...'")
    print(f"[Retriever] {len(results)} chunk(s) récupéré(s) :")
    for c, s in results:
        print(f"  score={s:.3f}  section={c.section}  chars={c.nb_chars}")

    return context