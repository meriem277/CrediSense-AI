"""
Script de test interactif pour le pipeline RAG.
Lance depuis la racine du projet :
    python scripts/chat_rag.py

Usage :
  - Tape une question pour interroger le dossier indexé
  - Tape 'debug' pour voir les chunks bruts + scores
  - Tape 'quit' pour quitter
"""

import sys
import os

# ── Racine du projet dans le path ──────────────────────────────────────────────
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from rag.vector_store import VectorStore
from rag.pipeline     import index_document, query_document
from rag.retriever    import retrieve_raw

# ══════════════════════════════════════════════════════════════════════════════
# CONFIGURATION — adapte ces valeurs à ton projet
# ══════════════════════════════════════════════════════════════════════════════

# ID du dossier à tester (doit correspondre à ce que tu as indexé)
DOSSIER_ID = "dossier_test_001"

# Texte brut de test (remplace par ton vrai texte OCR ou charge un fichier)
SAMPLE_TEXT = """
Jean Dupont, né le 12 mars 1985, est salarié chez TechCorp depuis 2015.
Son salaire net mensuel est de 3 200 euros.
Il dispose d'un apport personnel de 40 000 euros pour l'achat d'un bien immobilier.
Le bien visé est situé au 12 rue de la Paix, Paris 75001, estimé à 320 000 euros.
Jean n'a aucun crédit en cours et possède un livret A avec 8 000 euros d'épargne.
Son taux d'endettement actuel est de 0%. Il souhaite emprunter sur 20 ans.
"""

# Nombre de chunks à récupérer
TOP_K = 5

# ══════════════════════════════════════════════════════════════════════════════


def separator(char="─", width=60):
    print(char * width)


def print_banner():
    separator("═")
    print("  🔍  TEST INTERACTIF RAG")
    print(f"  Dossier : {DOSSIER_ID}")
    separator("═")
    print()


def print_help():
    print("  Commandes disponibles :")
    print("    <question>  → Interroger le dossier RAG")
    print("    debug       → Afficher les chunks bruts + scores")
    print("    reindex     → Ré-indexer le document de test")
    print("    quit        → Quitter")
    print()


def do_indexation(store: VectorStore) -> bool:
    """Indexe le document de test."""
    print("\n[*] Indexation du document de test...")
    try:
        stats = index_document(SAMPLE_TEXT, DOSSIER_ID, store)
        print(f"\n✅  Indexation réussie !")
        print(f"    Chunks créés     : {stats['nb_chunks']}")
        print(f"    Vecteurs FAISS   : {stats['nb_vecteurs']}")
        print(f"    Sections         : {stats['sections_detectees']}")
        print(f"    Compression      : {stats['taux_compression']}%")
        return True
    except Exception as e:
        print(f"\n❌  Erreur d'indexation : {e}")
        return False


def do_query(store: VectorStore, question: str):
    """Interroge le RAG et affiche le contexte récupéré."""
    print()
    separator()
    try:
        context = query_document(question, DOSSIER_ID, store, top_k=TOP_K)
        print("\n📄  CONTEXTE RAG RÉCUPÉRÉ :\n")
        print(context)
    except Exception as e:
        print(f"❌  Erreur lors de la requête : {e}")
    separator()
    print()


def do_debug(store: VectorStore, question: str):
    """Affiche les chunks bruts avec scores détaillés."""
    print()
    separator()
    print(f"  🐛  DEBUG — Question : \"{question}\"")
    separator()
    try:
        raw_results = retrieve_raw(store, DOSSIER_ID, question, top_k=TOP_K)
        if not raw_results:
            print("  Aucun résultat retourné par le store.")
        for i, (chunk, score) in enumerate(raw_results, 1):
            pertinence = "✅" if score >= 0.25 else "❌ (sous le seuil)"
            print(f"\n  [{i}] Score: {score:.4f}  {pertinence}")
            print(f"       Section : {chunk.section}")
            print(f"       Chunk ID: {chunk.id}")
            print(f"       Chars   : {chunk.nb_chars}")
            print(f"       Texte   : {chunk.texte[:120]}{'...' if len(chunk.texte) > 120 else ''}")
    except Exception as e:
        print(f"  ❌  Erreur debug : {e}")
    separator()
    print()


def load_text_from_file(path: str) -> str:
    """Charge un texte depuis un fichier (txt ou autre)."""
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


def main():
    print_banner()
    print_help()

    # Initialisation du store partagé
    store = VectorStore()

    # Option : charger un vrai fichier texte OCR
    # Décommente et adapte le chemin si tu as un vrai document
    # raw_text = load_text_from_file("data/mon_document.txt")
    # stats = index_document(raw_text, DOSSIER_ID, store)

    # Indexation initiale avec le texte de test
    ok = do_indexation(store)
    if not ok:
        print("Impossible de continuer sans indexation.")
        sys.exit(1)

    print("\n💬  Pose tes questions (ou tape 'quit') :\n")

    last_question = ""

    while True:
        try:
            user_input = input("  Question > ").strip()
        except (KeyboardInterrupt, EOFError):
            print("\n\n👋  Au revoir !")
            break

        if not user_input:
            continue

        cmd = user_input.lower()

        if cmd in ("quit", "exit", "q"):
            print("\n👋  Au revoir !")
            break

        elif cmd == "reindex":
            do_indexation(store)

        elif cmd == "debug":
            if not last_question:
                q = input("  Question à débugger > ").strip()
            else:
                q = last_question
                print(f"  (Debug de la dernière question : \"{q}\")")
            if q:
                do_debug(store, q)

        else:
            last_question = user_input
            do_query(store, user_input)


if __name__ == "__main__":
    main()