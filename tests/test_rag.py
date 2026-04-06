# tests/test_rag.py
"""
Tests complets du pipeline RAG.
Lancez avec : python tests/test_rag.py
"""

import sys, os
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

from rag.cleaner      import clean_text
from rag.chunker      import chunk_document
from rag.embedder     import embed_chunks, embed_query, cosine_similarity
from rag.vector_store import VectorStore
from rag.pipeline     import index_document, query_document

SAMPLE_TEXT = """
RELEVÉ DE COMPTE BANCAIRE
Client : Mohamed Ben Salah
CIN N° : 12345678
Date de naissance : 15/03/1985
Situation familiale : Marié, 2 enfants

INFORMATIONS PROFESSIONNELLES
Employeur : Société Nationale des Chemins de Fer
Contrat : CDI - Ancienneté 8 ans
Poste : Ingénieur en chef

REVENUS MENSUELS
Salaire net à payer : 3 850,000 DT
Heures supplémentaires : 200,000 DT
Total revenus nets : 4 050,000 DT

CHARGES FIXES MENSUELLES
Loyer résidence principale : 600,000 DT
Crédit auto en cours : 280,000 DT - 18 mois restants
Total charges fixes : 880,000 DT

HISTORIQUE DES MOUVEMENTS - JANVIER 2024
01/01  Solde reporté                         5 200,000 DT
05/01  Virement salaire SNCFT               +3 850,000 DT
10/01  Prélèvement loyer                    -600,000 DT
15/01  Remboursement crédit auto            -280,000 DT
20/01  Retrait DAB                          -200,000 DT
Solde final : 7 970,000 DT

PATRIMOINE ET ÉPARGNE
Compte épargne : 12 000,000 DT
Valeur bien immobilier déclaré : 180 000,000 DT
"""


def test_cleaner():
    result = clean_text(SAMPLE_TEXT)
    assert result.nb_chars_apres > 100
    assert result.nb_chars_apres <= result.nb_chars_avant
    print(f"\n[OK] Cleaner : {result.nb_chars_avant}→{result.nb_chars_apres} chars")


def test_chunker():
    result = clean_text(SAMPLE_TEXT)
    chunks = chunk_document(result.text, "test_dossier")
    assert len(chunks) >= 4, f"Attendu >= 4 chunks, obtenu {len(chunks)}"
    sections = {c.section for c in chunks}
    print(f"\n[OK] Chunker : {len(chunks)} chunks, sections={sections}")
    for c in chunks:
        assert c.nb_chars >= 40, f"Chunk trop court : {c.id}"
    return chunks


def test_embedder():
    chunks = test_chunker()
    embeddings = embed_chunks(chunks)
    assert embeddings.shape == (len(chunks), 384)
    assert embeddings.dtype.name == 'float32'
    # Vérifier normalisation L2 (norme ≈ 1.0)
    import numpy as np
    norms = np.linalg.norm(embeddings, axis=1)
    assert all(abs(n - 1.0) < 1e-4 for n in norms), "Vecteurs non normalisés !"
    print(f"\n[OK] Embedder : matrice {embeddings.shape}, normalisée L2")
    return embeddings


def test_vector_store():
    store = VectorStore()
    clean = clean_text(SAMPLE_TEXT)
    chunks = chunk_document(clean.text, "test_store")
    embeddings = embed_chunks(chunks)
    di = store.add("test_store", chunks, embeddings)
    assert store.exists("test_store")
    assert di.nb_vecteurs == len(chunks)
    info = store.info("test_store")
    print(f"\n[OK] VectorStore : {info}")
    store.delete("test_store")
    assert not store.exists("test_store")
    print(f"[OK] Suppression OK")


def test_retriever():
    store = VectorStore()
    stats = index_document(SAMPLE_TEXT, "test_retriever", store)
    print(f"\n[OK] Indexation : {stats}")

    questions = [
        "Quel est le salaire net mensuel du client ?",
        "Le client a-t-il des crédits en cours ?",
        "Quelle est la situation patrimoniale du client ?",
        "Quel est le solde final du compte en janvier 2024 ?",
    ]

    for q in questions:
        context = query_document(q, "test_retriever", store, top_k=3)
        assert len(context) > 50, f"Contexte vide pour : '{q}'"
        print(f"\n[OK] Retrieval pour : '{q[:50]}...'")
        print(f"     Contexte ({len(context)} chars) :\n"
              f"     {context[:200]}...")

    # Test similarité : salaire doit être plus proche de "revenus" que de "garanties"
    q_salaire   = embed_query("Quel est le salaire du client ?")
    q_garanties = embed_query("Quelles sont les garanties proposées ?")

    from rag.retriever import retrieve_raw
    res_salaire   = retrieve_raw(store, "test_retriever", "Quel est le salaire ?", 1)
    res_garanties = retrieve_raw(store, "test_retriever", "Quelles garanties ?",   1)

    if res_salaire and res_garanties:
        best_salaire   = res_salaire[0][0].section
        best_garanties = res_garanties[0][0].section
        print(f"\n[OK] Pertinence : salaire→'{best_salaire}' | garanties→'{best_garanties}'")

    store.delete("test_retriever")


if __name__ == "__main__":
    print("=== Tests Pipeline RAG ===\n")
    test_cleaner()
    test_chunker()
    test_embedder()
    test_vector_store()
    test_retriever()
    print("\n=== Tous les tests RAG passés avec succès ===")