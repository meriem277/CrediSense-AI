# tests/test_classification_cascade.py
"""
Évaluation de la classification hybride en cascade (section 4.5.2.2)

Ce script teste la VRAIE cascade de CrediSense
(services/document_classifier_service.py) :
  - score ≥ 0,55          → décision directe des embeddings
  - score < 0,20          → document non identifiable
  - 0,20 ≤ score < 0,55   → zone grise → appel RÉEL au modèle de langage

Pour chaque document, il compare :
  - la prédiction des embeddings SEULS (avant cascade) ;
  - la prédiction FINALE de la cascade (après appel éventuel au LLM).

Indicateurs calculés :
  - taux de documents traités localement (sans appel LLM) ;
  - nombre d'appels LLM et taux d'évitement ;
  - exactitude embeddings seuls vs cascade complète (gain apporté par le LLM) ;
  - exactitude dans la zone grise avant / après LLM ;
  - temps moyen : décision locale vs décision avec LLM ;
  - analyse de sensibilité du seuil haut (justification du 0,55).

Le jeu de test est le même que celui de test_classification_semantique.py
(textes intégrés + vrais textes OCR de tests/textes/).

Utilisation (depuis le dossier Python\\, venv activé) :
    python tests/test_classification_cascade.py
    python tests/test_classification_cascade.py --seulement-dossier

⚠ Ce script appelle le LLM (Groq / Mistral) pour les documents en zone grise :
  il consomme un peu de quota (quelques appels).
"""

import argparse
import csv
import statistics
import sys
import time
from collections import Counter
from datetime import datetime
from pathlib import Path

DOSSIER_TESTS = Path(__file__).resolve().parent
RACINE        = DOSSIER_TESTS.parent
sys.path.insert(0, str(RACINE))
sys.path.insert(0, str(DOSSIER_TESTS))

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

# Réutilise le jeu de test et les utilitaires du test sémantique
from test_classification_semantique import JEU_INTEGRE, charger_dossier, lire_resultat, fmt

SEUILS_TESTES = [0.40, 0.45, 0.50, 0.55, 0.60, 0.65, 0.70]


def pct(a: int, b: int) -> str:
    return f"{fmt(a / b * 100, 1)} %" if b else "–"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--seulement-dossier", action="store_true",
                        help="n'utiliser que les vrais textes de tests/textes/")
    args = parser.parse_args()

    print("=" * 110)
    print(" CrediSense — Évaluation de la classification hybride en cascade")
    print(f" {datetime.now():%d/%m/%Y %H:%M}")
    print("=" * 110)

    # 1. Chargement de la vraie cascade
    print("\nChargement de la cascade (embeddings + LLM)…")
    from services.document_classifier_service import (
        DocumentClassifierService, SEUIL_CONFIANCE_ELEVEE, SEUIL_CONFIANCE_BASSE,
    )
    service = DocumentClassifierService()
    print(f"Seuils utilisés : haut = {fmt(SEUIL_CONFIANCE_ELEVEE)}  |  bas = {fmt(SEUIL_CONFIANCE_BASSE)}")

    echantillons = [] if args.seulement_dossier else list(JEU_INTEGRE)
    reels = charger_dossier(DOSSIER_TESTS / "textes")
    echantillons += reels
    if not echantillons:
        print("Aucun échantillon à tester.")
        return
    print(f"Échantillons : {len(echantillons)} ({len(echantillons) - len(reels)} intégrés, {len(reels)} réels)\n")

    service.embeddings_classifier.classify("préchauffage du modèle", None)

    # 2. Classification
    lignes = []
    entete = (f"{'Document':<20}{'Attendu':<22}{'Embeddings':<22}{'Conf.':>6}  "
              f"{'Méthode':<28}{'Final':<22}{'ms':>7}  Avant Après")
    print(entete)
    print("-" * len(entete))

    for ident, attendu, langue, texte in echantillons:
        # a) Embeddings seuls (référence "avant cascade")
        emb = service.embeddings_classifier.classify(texte, None)
        pred_emb, conf = lire_resultat(emb)

        # b) Cascade complète (chronométrée)
        t = time.perf_counter()
        final = service.classify(texte, None)
        duree = (time.perf_counter() - t) * 1000

        methode = final.get("methode", "?")
        pred_final, _ = lire_resultat(final)
        if methode == "embeddings_faible_confiance":
            pred_final = "AUTRE"          # non identifiable

        appel_llm = methode in ("llm_fallback", "embeddings_llm_indisponible")
        # La zone et la confiance viennent de la décision réelle de la cascade
        if "confiance_embeddings_initiale" in final:
            conf = float(final["confiance_embeddings_initiale"])
        elif methode in ("embeddings", "embeddings_faible_confiance", "embeddings_llm_indisponible"):
            conf = float(final.get("confiance", conf))
        zone = {"embeddings": "directe", "embeddings_faible_confiance": "faible"}.get(
            methode, "grise" if appel_llm else
            ("directe" if conf >= SEUIL_CONFIANCE_ELEVEE else "faible" if conf < SEUIL_CONFIANCE_BASSE else "grise"))
        pred_emb_eff = "AUTRE" if zone == "faible" else pred_emb

        ok_avant = pred_emb_eff == attendu
        ok_apres = pred_final == attendu

        lignes.append({
            "document": ident, "langue": langue, "attendu": attendu,
            "predit_embeddings": pred_emb_eff, "confiance_embeddings": round(conf, 4),
            "zone": zone, "methode": methode, "appel_llm": appel_llm,
            "llm_provider": final.get("provider"), "predit_final": pred_final,
            "duree_ms": round(duree, 1), "correct_avant": ok_avant, "correct_apres": ok_apres,
        })
        print(f"{ident[:19]:<20}{attendu:<22}{pred_emb_eff:<22}{fmt(conf):>6}  "
              f"{methode:<28}{pred_final:<22}{duree:>7.0f}  "
              f"{'✓' if ok_avant else '✗':^5} {'✓' if ok_apres else '✗':^5}")

    # 3. Indicateurs
    n          = len(lignes)
    locaux     = [l for l in lignes if not l["appel_llm"]]
    avec_llm   = [l for l in lignes if l["appel_llm"]]
    echecs_llm = [l for l in lignes if l["methode"] == "embeddings_llm_indisponible"]
    grise      = [l for l in lignes if l["zone"] == "grise"]
    zones      = Counter(l["zone"] for l in lignes)
    ok_avant   = sum(l["correct_avant"] for l in lignes)
    ok_apres   = sum(l["correct_apres"] for l in lignes)

    print("\n" + "=" * 110)
    print(" INDICATEURS DE LA CASCADE")
    print("=" * 110)
    print(f" Documents testés                                   : {n}")
    print(f"   décision directe (score ≥ {fmt(SEUIL_CONFIANCE_ELEVEE)})                  : "
          f"{zones['directe']:>3}  ({pct(zones['directe'], n)})")
    print(f"   zone grise ({fmt(SEUIL_CONFIANCE_BASSE)} ≤ score < {fmt(SEUIL_CONFIANCE_ELEVEE)}) → LLM          : "
          f"{zones['grise']:>3}  ({pct(zones['grise'], n)})")
    print(f"   non identifiable (score < {fmt(SEUIL_CONFIANCE_BASSE)})                 : "
          f"{zones['faible']:>3}  ({pct(zones['faible'], n)})")
    print()
    print(f" Documents traités localement (sans LLM)            : {len(locaux)}/{n}  ({pct(len(locaux), n)})")
    print(f" Appels au modèle de langage                        : {len(avec_llm)}  "
          f"(évités : {n - len(avec_llm)}, soit {pct(n - len(avec_llm), n)})")
    if echecs_llm:
        print(f"   dont LLM indisponible (verdict embeddings gardé) : {len(echecs_llm)}")
    print()
    print(f" Exactitude — embeddings seuls                      : {ok_avant}/{n}  ({pct(ok_avant, n)})")
    print(f" Exactitude — cascade complète                      : {ok_apres}/{n}  ({pct(ok_apres, n)})")
    gain = (ok_apres - ok_avant) / n * 100
    print(f" Gain apporté par la cascade                        : {'+' if gain >= 0 else ''}{fmt(gain, 1)} points")
    if grise:
        g_avant = sum(l["correct_avant"] for l in grise)
        g_apres = sum(l["correct_apres"] for l in grise)
        print(f" Zone grise — exactitude avant LLM                  : {g_avant}/{len(grise)}  ({pct(g_avant, len(grise))})")
        print(f" Zone grise — exactitude après LLM                  : {g_apres}/{len(grise)}  ({pct(g_apres, len(grise))})")
    print()
    if locaux:
        print(f" Temps moyen — décision locale                      : {fmt(statistics.mean(l['duree_ms'] for l in locaux), 1)} ms")
    if avec_llm:
        print(f" Temps moyen — décision avec LLM                    : {fmt(statistics.mean(l['duree_ms'] for l in avec_llm), 1)} ms")
    print(f" Temps moyen — tous documents                       : {fmt(statistics.mean(l['duree_ms'] for l in lignes), 1)} ms")

    # Corrections et régressions du LLM
    corriges = [l for l in avec_llm if not l["correct_avant"] and l["correct_apres"]]
    degrades = [l for l in avec_llm if l["correct_avant"] and not l["correct_apres"]]
    if corriges:
        print("\n Erreurs des embeddings corrigées par le LLM :")
        for l in corriges:
            print(f"   {l['document']:<22} {l['predit_embeddings']} → {l['predit_final']}  (attendu {l['attendu']})")
    if degrades:
        print("\n Bonnes réponses des embeddings modifiées à tort par le LLM :")
        for l in degrades:
            print(f"   {l['document']:<22} {l['predit_embeddings']} → {l['predit_final']}  (attendu {l['attendu']})")
    erreurs = [l for l in lignes if not l["correct_apres"]]
    if erreurs:
        print("\n Erreurs restantes après cascade :")
        for l in erreurs:
            print(f"   {l['document']:<22} attendu {l['attendu']:<22} → {l['predit_final']}  ({l['methode']})")

    # 4. Sensibilité du seuil haut (sans nouvel appel LLM)
    print("\n Analyse de sensibilité du seuil haut (embeddings uniquement) :")
    print(f"   {'Seuil':>6}  {'Décidés localement':>20}  {'Exactitude de ces décisions':>28}")
    for s in SEUILS_TESTES:
        directs = [l for l in lignes if l["confiance_embeddings"] >= s]
        ok = sum(l["predit_embeddings"] == l["attendu"] for l in directs)
        marque = "  ← seuil retenu" if abs(s - SEUIL_CONFIANCE_ELEVEE) < 1e-9 else ""
        print(f"   {fmt(s):>6}  {len(directs):>8}/{n:<3} ({pct(len(directs), n):>7})  "
              f"{ok:>10}/{len(directs):<3} ({pct(ok, len(directs)):>7}){marque}")

    # 5. Export
    dossier_res = DOSSIER_TESTS / "resultats"
    dossier_res.mkdir(exist_ok=True)
    chemin = dossier_res / f"cascade_{datetime.now():%Y%m%d_%H%M}.csv"
    with open(chemin, "w", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=list(lignes[0].keys()), delimiter=";")
        writer.writeheader()
        writer.writerows(lignes)
    print(f"\n Détail enregistré : {chemin}")
    print("=" * 110)


if __name__ == "__main__":
    main()