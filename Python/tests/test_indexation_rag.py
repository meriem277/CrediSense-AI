# tests/test_indexation_rag.py
"""
Démonstration de l'indexation RAG (section « Préparation et indexation des documents »)

Utilise le VRAI service du chatbot (services/chatbot_service.py) pour :
  1. indexer deux dossiers clients fictifs (un index FAISS par dossierId) ;
  2. afficher les statistiques d'indexation : documents détectés, paragraphes,
     segments (chunks), taille maximale d'un segment, dimension des vecteurs,
     type d'index, temps de construction ;
  3. montrer un exemple de segment tel qu'il est vectorisé (en-tête + texte) ;
  4. vérifier l'ISOLATION : une recherche dans le dossier A ne renvoie jamais
     de contenu du dossier B ;
  5. vérifier la COUVERTURE : une question multi-documents récupère des
     segments de chaque document du dossier.

Aucun appel au modèle de langage : seule la partie indexation / recherche est testée.

Utilisation (depuis Python\\, venv activé) :
    python tests/test_indexation_rag.py
"""

import sys
import time
from collections import Counter
from pathlib import Path

RACINE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RACINE))

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

# ── Deux dossiers clients FICTIFS ─────────────────────────────────────────────
DOSSIER_A = ("dossier-A-trabelsi", [
    "BULLETIN DE PAIE\nOMEGA SERVICES SARL — Sousse\nPériode : août 2026\n"
    "Salariée : Mme Sana TRABELSI — Chargée de clientèle\nContrat : CDI\n"
    "Salaire de base : 2 600,000\nPrime de présence : 150,000\nSalaire brut : 2 840,000\n"
    "Cotisation CNSS : 260,712\nRetenue IRPP : 298,500\nNet à payer : 2 280,788 DT",

    "RELEVÉ DE COMPTE\nTitulaire : Mme Sana TRABELSI\nPériode du 01/08/2026 au 31/08/2026\n"
    "Ancien solde créditeur : 1 845,320\n03/08 VIREMENT SALAIRE OMEGA SERVICES 2 280,788\n"
    "06/08 RETRAIT DAB 300,000\n18/08 ECHEANCE PRET AUTO 410,000\n"
    "Nouveau solde créditeur : 3 336,458 DT",

    "ATTESTATION DE TRAVAIL\nNous soussignés, OMEGA SERVICES SARL, attestons que Madame "
    "Sana TRABELSI est employée au sein de notre société depuis le 15/03/2020\n"
    "en qualité de chargée de clientèle, dans le cadre d'un contrat à durée indéterminée.\n"
    "Fait pour servir et valoir ce que de droit.",

    "REPUBLIQUE TUNISIENNE\nCarte d'identité nationale\nNom : TRABELSI\nPrénom : Sana\n"
    "Date de naissance : 21/06/1993\nLieu de naissance : Sousse\nCIN 07654321",
])

DOSSIER_B = ("dossier-B-jlassi", [
    "BULLETIN DE PAIE\nDELTA INDUSTRIE SA — Monastir\nPériode : juillet 2026\n"
    "Salarié : M. Karim JLASSI — Technicien supérieur\nSalaire de base : 1 950,000\n"
    "Salaire brut : 2 070,000\nCotisations CNSS : 190,026\nNet à payer : 1 704,574 DT",

    "EXTRAIT DE COMPTE\nTitulaire : M. Karim JLASSI\nAncien solde : 920,150\n"
    "05/07 VIR SALAIRE DELTA INDUSTRIE 1 704,574\n09/07 RETRAIT DAB 200,000\n"
    "Nouveau solde : 2 363,424",
])


def ligne(titre: str, valeur) -> None:
    print(f"   {titre:<42}: {valeur}")


def main():
    print("=" * 100)
    print(" CrediSense — Indexation RAG des documents (FAISS, un index par dossier)")
    print("=" * 100)

    print("\nChargement du modèle d'embeddings…")
    from services import chatbot_service as cs
    if not hasattr(cs, "detecter_type_document"):
        print("⚠ Ancienne version de chatbot_service.py : remplacez-la par la dernière version.")
        return
    service = cs.ChatbotService()

    # ── 1. Indexation des deux dossiers ──────────────────────────────────────
    for dossier_id, textes in (DOSSIER_A, DOSSIER_B):
        nb_paragraphes = sum(len([l for l in t.split("\n") if l.strip()]) for t in textes)
        t0 = time.perf_counter()
        service._construire_index(dossier_id, None, textes)
        duree = (time.perf_counter() - t0) * 1000

        store  = service._indexes[dossier_id]
        index  = store["index"]
        chunks = store["chunks"]
        tailles = [len(c["text"]) // 4 for c in chunks]
        par_doc = Counter(c.get("document", "?") for c in chunks)

        print(f"\n── Dossier « {dossier_id} » " + "─" * (70 - len(dossier_id)))
        ligne("Documents indexés", len(textes))
        for doc, n in par_doc.items():
            ligne(f"   {doc}", f"{n} segment(s)")
        ligne("Paragraphes (lignes de texte)", nb_paragraphes)
        ligne("Segments (chunks)", len(chunks))
        ligne("Taille maximale d'un segment", f"{max(tailles)} tokens estimés (limite : {cs.MAX_TOKENS_CHUNK})")
        ligne("Vecteurs dans l'index", index.ntotal)
        ligne("Dimension des vecteurs", getattr(index, "d", "?"))
        ligne("Type d'index", type(index).__name__ + " (produit scalaire = cosinus, vecteurs normalisés)")
        ligne("Temps de construction", f"{duree:,.0f} ms".replace(",", " "))

    print(f"\n   Index en mémoire : {len(service._indexes)} → {list(service._indexes.keys())}")

    # ── 2. Exemple de segment vectorisé ──────────────────────────────────────
    chunks_a = service._indexes[DOSSIER_A[0]]["chunks"]
    exemple = next((c for c in chunks_a if "Fiche de paie" in c.get("document", "")), chunks_a[0])
    print("\n── Exemple de segment tel qu'il est vectorisé " + "─" * 54)
    for l in service._build_embedding_text(exemple).split("\n"):
        print(f"   │ {l}")

    # ── 3. Test d'isolation entre dossiers ───────────────────────────────────
    question = "Quel est le salaire net de Karim JLASSI chez DELTA INDUSTRIE ?"
    print("\n── Test d'isolation " + "─" * 80)
    print(f"   Question posée sur le dossier A : « {question} »")
    resultats = service._retriever(question, DOSSIER_A[0])
    fuite = [r for r in resultats if "JLASSI" in r["text"] or "DELTA" in r["text"]]
    for r in resultats[:3]:
        apercu = r["text"].replace("\n", " | ")[:60]
        print(f"   score {r['score']:.3f}  [{r.get('document', '?')}]  {apercu}…")
    print(f"   Segments du dossier B retournés : {len(fuite)}  → "
          f"{'ISOLATION RESPECTÉE ✓' if not fuite else 'FUITE DÉTECTÉE ✗'}")

    # ── 4. Test de couverture multi-documents ────────────────────────────────
    question = ("Le salaire de la fiche de paie est-il cohérent avec le relevé bancaire "
                "et l'attestation d'emploi ?")
    print("\n── Test de couverture multi-documents " + "─" * 62)
    print(f"   Question : « {question} »")
    resultats = service._retriever(question, DOSSIER_A[0])
    couverts = Counter(r.get("document", "?") for r in resultats)
    for doc in sorted(Counter(c.get("document", "?") for c in chunks_a)):
        print(f"   {doc:<40} {couverts.get(doc, 0)} segment(s) récupéré(s)  "
              f"{'✓' if couverts.get(doc) else '✗'}")
    tous = all(couverts.get(d) for d in set(c.get("document") for c in chunks_a))
    print(f"   Segments récupérés : {len(resultats)}  → "
          f"{'tous les documents du dossier sont couverts ✓' if tous else 'document(s) manquant(s) ✗'}")
    print("=" * 100)


if __name__ == "__main__":
    main()