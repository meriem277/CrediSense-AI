"""
Mesure des indicateurs de performance (KPI) de chaque étape de la chaîne CrediSense :
OCR, classification, RAG (recherche + réponses), moteur de décision.

Tout est mesuré sur le jeu de test FICTIF du dépôt (test-data/ et Python/tests/documents/).
Rien n'est inventé : les résultats sont écrits dans benchmarks/resultats/kpi_<section>.json et
c'est ce fichier que le rapport cite.

Lancer depuis le dossier Python/ (environnement virtuel activé) :
    python benchmarks/mesurer_kpi.py ocr
    python benchmarks/mesurer_kpi.py classification [--sans-llm]
    python benchmarks/mesurer_kpi.py rag [--sans-llm]
    python benchmarks/mesurer_kpi.py decision [--sans-llm] [--runs 5]
    python benchmarks/mesurer_kpi.py all

--sans-llm : aucun appel réseau (mesures déterministes seulement). Sans cette option, les étapes
qui utilisent un modèle de langage appellent le fournisseur configuré dans .env (jeu de test fictif).
"""
import argparse
import json
import logging
import os
import platform
import random
import re
import statistics
import sys
import time
import unicodedata
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

RACINE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RACINE))
DEPOT = RACINE.parent
DOSSIER_TEST = DEPOT / "test-data" / "dossier-12015060"
DOCS_CLASSIF = RACINE / "tests" / "documents"
SORTIE = Path(__file__).resolve().parent / "resultats"
SORTIE.mkdir(exist_ok=True)

logging.disable(logging.WARNING)
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass


# ── Outils communs ───────────────────────────────────────────────────────────

def sans_accents(texte: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFD", texte) if unicodedata.category(c) != "Mn")


def norm(texte: str) -> str:
    return sans_accents(texte).lower()


def compact(texte: str) -> str:
    """Pour retrouver une valeur quelle que soit la mise en forme (espaces, casse, accents)."""
    return re.sub(r"\s+", "", norm(texte))


def jetons(texte: str, arabe: bool = False) -> list[str]:
    motif = r"[؀-ۿ]{2,}" if arabe else r"[a-z0-9]{2,}"
    return re.findall(motif, texte if arabe else norm(texte))


def percentile(valeurs: list[float], p: float) -> float:
    if not valeurs:
        return float("nan")
    ordre = sorted(valeurs)
    k = (len(ordre) - 1) * p
    bas, haut = int(k), min(int(k) + 1, len(ordre) - 1)
    return ordre[bas] + (ordre[haut] - ordre[bas]) * (k - bas)


def stats_ms(valeurs: list[float]) -> dict:
    if not valeurs:
        return {"n": 0}
    return {"n": len(valeurs), "moyenne": round(statistics.mean(valeurs), 1),
            "mediane": round(statistics.median(valeurs), 1),
            "p95": round(percentile(valeurs, 0.95), 1),
            "min": round(min(valeurs), 1), "max": round(max(valeurs), 1)}


def machine() -> dict:
    import multiprocessing
    return {"os": platform.platform(), "python": platform.python_version(),
            "cpu": platform.processor() or platform.machine(), "coeurs": multiprocessing.cpu_count(),
            "gpu": "non (calcul sur processeur)"}


def ecrire(section: str, donnees: dict) -> None:
    donnees = {"date": datetime.now().strftime("%Y-%m-%d %H:%M"), "machine": machine(), **donnees}
    chemin = SORTIE / f"kpi_{section}.json"
    chemin.write_text(json.dumps(donnees, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n→ résultats écrits dans {chemin.relative_to(DEPOT)}")


def nb_pages(chemin: Path) -> int:
    import fitz
    with fitz.open(chemin) as pdf:
        return len(pdf)


# ── 1. OCR ───────────────────────────────────────────────────────────────────

# Valeurs attendues, tirées du LISEZMOI du jeu de test : on mesure si l'OCR les retrouve.
CHAMPS_ATTENDUS = {
    "CIN": ["12015060", "TRABELSI", "Yassine", "14/03/1990", "Tunis"],
    "FICHE_PAIE": ["Exemple Tech", "2 400,000", "2 100,000"],
    "RELEVE_BANCAIRE": ["250,000", "600,000", "2 853,650"],
    "ATTESTATION_EMPLOI": ["Exemple Tech", "01/09/2021", "Ingénieur", "TRABELSI"],
    "JUSTIFICATIF_DOMICILE": ["Jasmins", "Ariana", "88,400"],
}


def rappel_champs(texte: str, champs: list[str]) -> tuple[int, list[str]]:
    t = compact(texte)
    manquants = [c for c in champs if compact(c) not in t]
    return len(champs) - len(manquants), manquants


def charger_ocr():
    from services.ocr_service import OcrService
    t0 = time.time()
    service = OcrService()
    return service, round(time.time() - t0, 1)


def mesurer_ocr() -> dict:
    print("== OCR ==")
    service, chargement = charger_ocr()
    print(f"modèles chargés en {chargement} s")
    lignes, tous_natifs, tous_scans = [], [], []
    for type_doc, champs in CHAMPS_ATTENDUS.items():
        natif = DOSSIER_TEST / "natifs" / f"{type_doc}.pdf"
        scan = DOSSIER_TEST / "scannes" / f"{type_doc}.pdf"

        service._cache.clear()
        t0 = time.time()
        r_natif = service.extraire(str(natif))
        d_natif = (time.time() - t0) * 1000
        service._cache.clear()
        t0 = time.time()
        r_scan = service.extraire(str(scan))
        d_scan = (time.time() - t0) * 1000

        pages = nb_pages(scan)
        ref, ocr = r_natif.get("texte", ""), r_scan.get("texte", "")

        # F1 « sac de mots » : insensible à l'ordre de lecture (différent entre texte natif et OCR)
        ref_j, ocr_j = Counter(jetons(ref)), Counter(jetons(ocr))
        commun = sum((ref_j & ocr_j).values())
        rappel = commun / max(1, sum(ref_j.values()))
        precision = commun / max(1, sum(ocr_j.values()))
        f1 = 2 * rappel * precision / max(1e-9, rappel + precision)

        n_natif, manq_natif = rappel_champs(ref, champs)
        n_scan, manq_scan = rappel_champs(ocr, champs)
        tous_natifs.append((n_natif, len(champs)))
        tous_scans.append((n_scan, len(champs)))
        lignes.append({
            "document": type_doc, "pages": pages,
            "natif": {"cas": r_natif.get("cas"), "moteur": r_natif.get("moteur"), "duree_ms": round(d_natif, 1),
                      "champs_retrouves": f"{n_natif}/{len(champs)}", "manquants": manq_natif},
            "scan": {"cas": r_scan.get("cas"), "moteur": r_scan.get("moteur"), "duree_ms": round(d_scan, 1),
                     "ms_par_page": round(d_scan / pages, 1),
                     "champs_retrouves": f"{n_scan}/{len(champs)}", "manquants": manq_scan,
                     "mots_rappel": round(rappel, 3), "mots_precision": round(precision, 3), "mots_f1": round(f1, 3),
                     "statut": r_scan.get("statut")},
        })
        print(f"{type_doc:22s} natif {d_natif:7.0f} ms ({n_natif}/{len(champs)}) | scan {d_scan:8.0f} ms "
              f"({pages} p.) champs {n_scan}/{len(champs)} F1 mots {f1:.2f}")

    ok_n, tot_n = sum(a for a, _ in tous_natifs), sum(b for _, b in tous_natifs)
    ok_s, tot_s = sum(a for a, _ in tous_scans), sum(b for _, b in tous_scans)
    scans_ms = [l["scan"]["duree_ms"] for l in lignes]
    pages_scan = sum(l["pages"] for l in lignes)
    synthese = {
        "chargement_modeles_s": chargement,
        "champs_natif": f"{ok_n}/{tot_n}", "taux_champs_natif": round(ok_n / tot_n, 3),
        "champs_scan": f"{ok_s}/{tot_s}", "taux_champs_scan": round(ok_s / tot_s, 3),
        "natif_duree_ms": stats_ms([l["natif"]["duree_ms"] for l in lignes]),
        "scan_duree_ms": stats_ms(scans_ms),
        "scan_ms_par_page": round(sum(scans_ms) / pages_scan, 1), "pages_scan": pages_scan,
        "mots_f1_moyen": round(statistics.mean(l["scan"]["mots_f1"] for l in lignes), 3),
    }
    print(f"\nChamps retrouvés : natif {ok_n}/{tot_n}, scan {ok_s}/{tot_s} ; "
          f"{synthese['scan_ms_par_page']} ms/page en OCR")
    return {"documents": lignes, "synthese": synthese}


# ── 2. Classification ────────────────────────────────────────────────────────

def corpus_classification() -> list[tuple[str, str, Path]]:
    """(nom, type attendu, chemin) : les 16 documents du banc d'essai + les 10 du dossier de test."""
    corpus = []
    for f in sorted(DOCS_CLASSIF.glob("*.pdf")):
        corpus.append((f.stem, f.name.split("__")[0], f))
    for sous in ("natifs", "scannes"):
        for f in sorted((DOSSIER_TEST / sous).glob("*.pdf")):
            corpus.append((f"dossier_{sous[:-1] if sous == 'natifs' else 'scan'}_{f.stem}", f.stem, f))
    return corpus


def f1_macro(attendus: list[str], predits: list[str]) -> tuple[float, dict]:
    classes = sorted(set(attendus) | set(predits))
    par_classe = {}
    for c in classes:
        vp = sum(1 for a, p in zip(attendus, predits) if a == c and p == c)
        fp = sum(1 for a, p in zip(attendus, predits) if a != c and p == c)
        fn = sum(1 for a, p in zip(attendus, predits) if a == c and p != c)
        prec = vp / (vp + fp) if vp + fp else 0.0
        rap = vp / (vp + fn) if vp + fn else 0.0
        par_classe[c] = {"precision": round(prec, 3), "rappel": round(rap, 3),
                         "f1": round(2 * prec * rap / (prec + rap), 3) if prec + rap else 0.0,
                         "support": sum(1 for a in attendus if a == c)}
    classes_presentes = [c for c in classes if par_classe[c]["support"] > 0]
    return round(statistics.mean(par_classe[c]["f1"] for c in classes_presentes), 3), par_classe


def mesurer_classification(sans_llm: bool) -> dict:
    print("== Classification ==")
    service, _ = charger_ocr()
    corpus = corpus_classification()

    textes = {}
    for nom, _, chemin in corpus:
        service._cache.clear()
        textes[nom] = service.extraire(str(chemin)).get("texte", "")
    print(f"{len(corpus)} documents lus")

    from services.classification_regles import diagnostiquer_regles, verdict_depuis_diagnostic
    from services.document_classifier_service import DocumentClassifierService
    classifieur = DocumentClassifierService()

    # Niveau 0 seul, niveau 1 seul, cascade sans LLM, cascade complète
    def regles(texte):
        t0 = time.time()
        v = verdict_depuis_diagnostic(diagnostiquer_regles(texte))
        return (v["type_document"] if v else None), (time.time() - t0) * 1000

    def embeddings(texte):
        t0 = time.time()
        r = classifieur.embeddings_classifier.classify(texte)
        return r["type_document"], r["confiance"], (time.time() - t0) * 1000

    classifieur.embeddings_classifier.classify(next(iter(textes.values())))   # préchauffage

    def cascade(texte, avec_llm):
        original = classifieur.llm_classifier.classify
        if not avec_llm:
            classifieur.llm_classifier.classify = lambda *a, **k: {"echec": True, "justification": "LLM désactivé"}
        try:
            t0 = time.time()
            r = classifieur.classify(texte)
            return r, (time.time() - t0) * 1000
        finally:
            classifieur.llm_classifier.classify = original

    lignes = []
    for nom, attendu, _ in corpus:
        texte = textes[nom]
        pred_regles, d_regles = regles(texte)
        pred_emb, conf_emb, d_emb = embeddings(texte)
        r_sans, d_sans = cascade(texte, False)
        ligne = {"document": nom, "attendu": attendu,
                 "regles": pred_regles, "regles_ms": round(d_regles, 2),
                 "embeddings": pred_emb, "embeddings_confiance": round(conf_emb, 3), "embeddings_ms": round(d_emb, 1),
                 "cascade_sans_llm": r_sans["type_document"], "cascade_sans_llm_methode": r_sans.get("methode"),
                 "cascade_sans_llm_ms": round(d_sans, 1)}
        if not sans_llm:
            r_llm, d_llm = cascade(texte, True)
            ligne.update({"cascade": r_llm["type_document"], "cascade_methode": r_llm.get("methode"),
                          "cascade_ms": round(d_llm, 1)})
        lignes.append(ligne)

    att = [l["attendu"] for l in lignes]

    def bloc(cle_pred, cle_ms=None, cle_methode=None):
        preds = [l[cle_pred] or "NON_TRANCHE" for l in lignes]
        justes = sum(1 for a, p in zip(att, preds) if a == p)
        f1, par_classe = f1_macro(att, preds)
        res = {"exactitude": round(justes / len(lignes), 3), "justes": f"{justes}/{len(lignes)}", "f1_macro": f1,
               "erreurs": [f"{l['document']} : {l['attendu']} → {l[cle_pred]}" for l in lignes
                           if (l[cle_pred] or "NON_TRANCHE") != l["attendu"]]}
        if cle_ms:
            res["duree_ms"] = stats_ms([l[cle_ms] for l in lignes])
        if cle_methode:
            res["repartition_methodes"] = dict(Counter(l[cle_methode] for l in lignes))
        return res, par_classe

    # Règles : on ne mesure l'exactitude que là où elles tranchent (sinon elles laissent la main)
    tranchees = [l for l in lignes if l["regles"]]
    regles_res = {"couverture": f"{len(tranchees)}/{len(lignes)}", "taux_couverture": round(len(tranchees) / len(lignes), 3),
                  "precision_quand_elles_tranchent": round(sum(1 for l in tranchees if l["regles"] == l["attendu"]) / max(1, len(tranchees)), 3),
                  "erreurs": [f"{l['document']} : {l['attendu']} → {l['regles']}" for l in tranchees if l["regles"] != l["attendu"]],
                  "duree_ms": stats_ms([l["regles_ms"] for l in lignes])}
    emb_res, _ = bloc("embeddings", "embeddings_ms")
    sans_res, par_classe = bloc("cascade_sans_llm", "cascade_sans_llm_ms", "cascade_sans_llm_methode")
    synthese = {"documents": len(lignes), "regles": regles_res, "embeddings_seuls": emb_res, "cascade_sans_llm": sans_res,
                "classes": par_classe}
    if not sans_llm:
        casc_res, par_classe_llm = bloc("cascade", "cascade_ms", "cascade_methode")
        synthese["cascade_complete"] = casc_res
        synthese["classes"] = par_classe_llm
        appels = [l for l in lignes if l.get("cascade_methode") == "llm_fallback"]
        synthese["appels_llm"] = {"nombre": len(appels), "part": round(len(appels) / len(lignes), 3),
                                  "duree_ms": stats_ms([l["cascade_ms"] for l in appels])}
    print(json.dumps({k: v for k, v in synthese.items() if k != "classes"}, ensure_ascii=False, indent=2))
    return {"documents": lignes, "synthese": synthese}


# ── 3. RAG ───────────────────────────────────────────────────────────────────

# (question, valeur attendue dans un extrait, type : « exacte » = montant / numéro, « paraphrase » = tournure libre)
QUESTIONS_RAG = [
    ("Quel est le numéro de CIN du client ?", "12015060", "exacte"),
    ("Quel est le net à payer sur la fiche de paie ?", "2 100,000", "exacte"),
    ("Quel est le salaire brut ?", "2 400,000", "exacte"),
    ("Quelle est la date d'embauche ?", "01/09/2021", "exacte"),
    ("Quelle est l'échéance du prêt en cours ?", "250,000", "exacte"),
    ("Quel est le loyer mensuel ?", "600,000", "exacte"),
    ("Quel est le solde au 30 septembre ?", "2 853,650", "exacte"),
    ("Quel est le montant de la facture d'électricité ?", "88,400", "exacte"),
    ("Quelle est la date de naissance ?", "14/03/1990", "exacte"),
    ("Quel est le lieu de naissance ?", "Tunis", "exacte"),
    ("Qui est l'employeur du client ?", "Exemple Tech", "exacte"),
    ("Quel poste occupe le client ?", "Ingénieur", "exacte"),
    ("Combien gagne le client par mois ?", "2 100,000", "paraphrase"),
    ("Où habite le client ?", "Jasmins", "paraphrase"),
    ("Depuis quand travaille-t-il dans l'entreprise ?", "01/09/2021", "paraphrase"),
    ("A-t-il un crédit en cours et de combien par mois ?", "250,000", "paraphrase"),
    ("Combien paie-t-il pour se loger ?", "600,000", "paraphrase"),
    ("Quelle est la ville de résidence ?", "Ariana", "paraphrase"),
    ("Quelle est la société qui l'emploie ?", "Exemple Tech", "paraphrase"),
    ("De quel montant dispose-t-il sur son compte à la fin du mois ?", "2 853,650", "paraphrase"),
    ("Quel est son nom de famille ?", "TRABELSI", "paraphrase"),
    ("Quel est son prénom ?", "Yassine", "paraphrase"),
]


def rangs_dense(store, modele, question, normaliser):
    import numpy as np
    vec = modele.encode([normaliser(question)], convert_to_numpy=True, normalize_embeddings=True).astype("float32")
    scores, indices = store["index"].search(vec, store["index"].ntotal)
    return [int(i) for i in indices[0] if i != -1]


def rangs_lexical(store, question):
    from services.recherche_hybride import tokeniser
    scores = store["bm25"].scores(tokeniser(question))
    return sorted((i for i in range(len(scores)) if scores[i] > 0), key=lambda i: scores[i], reverse=True)


def mesurer_rag(sans_llm: bool) -> dict:
    print("== RAG ==")
    service, _ = charger_ocr()
    textes = []
    for type_doc in CHAMPS_ATTENDUS:
        service._cache.clear()
        textes.append(service.extraire(str(DOSSIER_TEST / "natifs" / f"{type_doc}.pdf")).get("texte", ""))

    from services.chatbot_service import ChatbotService
    from services.recherche_hybride import fusion_rrf
    bot = ChatbotService()
    bot._encoder_lignes(["préchauffage du modèle"])

    t0 = time.time()
    bot.indexer_documents("kpi", textes)
    duree_index = (time.time() - t0) * 1000
    store = bot._indexes["kpi"]
    chunks = store["chunks"]
    taille = [len(c["text"]) for c in chunks]

    def contient(indices, attendu, k):
        cible = compact(attendu)
        return any(cible in compact(chunks[i]["text"]) for i in indices[:k])

    def rang_premier(indices, attendu):
        cible = compact(attendu)
        for r, i in enumerate(indices, start=1):
            if cible in compact(chunks[i]["text"]):
                return r
        return None

    modes = {"dense": [], "lexical": [], "hybride": []}
    detail, latences = [], {"dense": [], "hybride": []}
    for question, attendu, genre in QUESTIONS_RAG:
        t0 = time.time()
        dense = rangs_dense(store, bot._embedding_model, question, bot._normaliser_question)
        latences["dense"].append((time.time() - t0) * 1000)
        lexical = rangs_lexical(store, question)
        t0 = time.time()
        fusion = fusion_rrf([rangs_dense(store, bot._embedding_model, question, bot._normaliser_question),
                             rangs_lexical(store, question)])
        hybride = sorted(fusion, key=fusion.get, reverse=True)
        latences["hybride"].append((time.time() - t0) * 1000)
        for nom, classement in (("dense", dense), ("lexical", lexical), ("hybride", hybride)):
            modes[nom].append((genre, classement, attendu))
        detail.append({"question": question, "genre": genre, "attendu": attendu,
                       "rang_dense": rang_premier(dense, attendu), "rang_lexical": rang_premier(lexical, attendu),
                       "rang_hybride": rang_premier(hybride, attendu)})

    def metriques(entrees):
        res = {}
        for k in (1, 3, 5):
            res[f"rappel@{k}"] = round(sum(contient(c, a, k) for _, c, a in entrees) / len(entrees), 3)
        rangs = [rang_premier(c, a) for _, c, a in entrees]
        res["MRR"] = round(statistics.mean(1 / r if r else 0 for r in rangs), 3)
        return res

    resultats = {}
    for nom, entrees in modes.items():
        resultats[nom] = {"toutes": metriques(entrees),
                          "montants_et_numeros": metriques([e for e in entrees if e[0] == "exacte"]),
                          "tournures_libres": metriques([e for e in entrees if e[0] == "paraphrase"])}
    for nom, lat in (("dense", latences["dense"]), ("hybride", latences["hybride"])):
        resultats[nom]["latence_ms"] = stats_ms(lat)

    # Récupérateur réel du chatbot (avec garantie de couverture de chaque document)
    reel = []
    for question, attendu, _ in QUESTIONS_RAG:
        t0 = time.time()
        extraits = bot._retriever(question, "kpi")
        reel.append((time.time() - t0) * 1000)
    couverture = []
    for question, attendu, _ in QUESTIONS_RAG:
        extraits = bot._retriever(question, "kpi")
        couverture.append(any(compact(attendu) in compact(c["text"]) for c in extraits))
    recuperateur = {"rappel_dans_les_extraits_envoyes_au_LLM": round(sum(couverture) / len(couverture), 3),
                    "extraits_envoyes": len(bot._retriever(QUESTIONS_RAG[0][0], "kpi")),
                    "latence_ms": stats_ms(reel)}

    synthese = {"documents": len(textes), "chunks": len(chunks), "chars_par_chunk": {
                    "moyenne": round(statistics.mean(taille)), "min": min(taille), "max": max(taille)},
                "types_de_chunks": dict(Counter(c["chunk_type"] for c in chunks)),
                "indexation_ms": round(duree_index, 0), "questions": len(QUESTIONS_RAG),
                "modes": resultats, "recuperateur_reel": recuperateur}

    if not sans_llm:
        reponses = []
        for question, attendu, genre in QUESTIONS_RAG[:12]:
            t0 = time.time()
            r = bot.poser_question(question, "kpi", "12015060", None, textes)
            duree = (time.time() - t0) * 1000
            juste = compact(attendu) in compact(r.get("reponse", ""))
            reponses.append({"question": question, "attendu": attendu, "statut": r.get("statut"),
                             "juste": juste, "duree_ms": round(duree, 0), "provider": r.get("provider")})
        ok = [r for r in reponses if r["statut"] == "SUCCESS"]
        synthese["reponses"] = {
            "questions": len(reponses), "reponses_obtenues": len(ok),
            "exactitude": round(sum(r["juste"] for r in ok) / max(1, len(ok)), 3),
            "duree_ms": stats_ms([r["duree_ms"] for r in ok]),
            "echecs": [r["question"] for r in reponses if r["statut"] != "SUCCESS" or not r["juste"]]}
        detail = {"retrieval": detail, "reponses": reponses}
    print(json.dumps(synthese, ensure_ascii=False, indent=2))
    return {"detail": detail, "synthese": synthese}


# ── 4. Moteur de décision ────────────────────────────────────────────────────

def mensualite_reference(montant: float, mois: int, taux: float) -> float:
    """Mensualité trouvée par dichotomie sur un tableau d'amortissement : référence indépendante de la formule."""
    r = taux / 12
    bas, haut = 0.0, montant
    for _ in range(80):
        m = (bas + haut) / 2
        solde = montant
        for _ in range(mois):
            solde = solde * (1 + r) - m
        if solde > 0:
            bas = m
        else:
            haut = m
    return (bas + haut) / 2


def mesurer_decision(sans_llm: bool, runs: int) -> dict:
    print("== Décision ==")
    os.environ["CREDIT_TAUX_ANNUEL"] = "0.10"
    from services import agent_service
    from services.agent_service import (AgentService, appliquer_regles, calculer_mensualite, proposer_ajustements,
                                       DTI_ACCEPTABLE, MULTIPLE_SALAIRE, DUREE_MAX_MOIS, AGE_MAX_FIN_CREDIT)
    rnd = random.Random(2026)

    # (a) Exactitude du calcul : comparaison à un tableau d'amortissement indépendant
    ecarts = []
    for _ in range(300):
        montant, mois, taux = rnd.randrange(1000, 60000, 100), rnd.choice([6, 12, 24, 36, 48, 60, 72, 84]), rnd.uniform(0.05, 0.15)
        ecarts.append(abs(calculer_mensualite(montant, mois, taux) - mensualite_reference(montant, mois, taux)))
    calcul = {"profils": len(ecarts), "ecart_max_DT": round(max(ecarts), 6), "ecart_moyen_DT": round(statistics.mean(ecarts), 8)}
    print("calcul de la mensualité :", calcul)

    # (b) Cohérence interne : 20 000 dossiers aléatoires
    profils = []
    for _ in range(20000):
        revenu = rnd.randrange(800, 9000, 50)
        profils.append({"monthlyIncome": float(revenu),
                        "requestedAmount": float(rnd.randrange(1000, 60000, 500)),
                        "duration": rnd.choice([6, 12, 18, 24, 36, 48, 60, 72, 84]),
                        "existingDebts": float(rnd.choice([0, 0, 0, 100, 250, 400, 700, 1200])),
                        "clientAge": rnd.randrange(21, 72), "contractType": rnd.choice(["CDI", "CDD", "FONCTIONNAIRE"]),
                        "employmentStartDate": rnd.choice(["01/09/2021", "01/03/2026", "01/01/2015"]),
                        "paymentIncidents": rnd.choice([0, 0, 0, 1])})
    d_regles, d_offres = [], []
    offres_total = offres_invalides = avec_offre = candidats = 0
    for m in profils:
        t0 = time.perf_counter()
        r = appliquer_regles(dict(m), True)
        d_regles.append((time.perf_counter() - t0) * 1e6)
        dti = r["metrics"].get("dti")
        if dti is None or dti < DTI_ACCEPTABLE:
            continue
        candidats += 1
        t0 = time.perf_counter()
        prop = proposer_ajustements(r["metrics"], r["taux"], True, r["regulatoryChecks"])
        d_offres.append((time.perf_counter() - t0) * 1e6)
        if prop["offers"]:
            avec_offre += 1
        for o in prop["offers"]:
            offres_total += 1
            mens = calculer_mensualite(o["amount"], o["duration"], 0.10)
            dti_o = (mens + m["existingDebts"]) / m["monthlyIncome"] * 100
            age_fin = m["clientAge"] + o["duration"] / 12
            if not (dti_o < DTI_ACCEPTABLE and o["amount"] <= MULTIPLE_SALAIRE * m["monthlyIncome"]
                    and o["duration"] <= DUREE_MAX_MOIS and age_fin <= AGE_MAX_FIN_CREDIT + 1e-9
                    and o["amount"] <= m["requestedAmount"] + 1e-9):
                offres_invalides += 1
    moteur = {"dossiers_simules": len(profils),
              "regles_microsecondes": stats_ms(d_regles),
              "dossiers_endettement_>=30%": candidats, "dossiers_avec_au_moins_une_offre": avec_offre,
              "part_avec_offre": round(avec_offre / max(1, candidats), 3),
              "offres_generees": offres_total, "offres_invalides": offres_invalides,
              "proposition_microsecondes": stats_ms(d_offres)}
    print("moteur :", moteur)

    # (b bis) Grille de score : reproductibilité, bornes, vitesse
    from services.score_grille import calculer_score
    from services.agent_service import PARAMETRES, anciennete_en_mois
    reference = {"dti": 10.87, "contractType": "CDI", "paymentIncidents": 0, "monthlyIncome": 2100.0, "requestedAmount": 9000.0}
    repetitions = {json.dumps(calculer_score(PARAMETRES, reference, 61), sort_keys=True) for _ in range(2000)}
    scores, durees_grille, hors_bornes, provisoires = [], [], 0, 0
    for m in profils[:5000]:
        r = appliquer_regles(dict(m), True)
        t0 = time.perf_counter()
        g = calculer_score(PARAMETRES, r["metrics"], anciennete_en_mois(m["employmentStartDate"]))
        durees_grille.append((time.perf_counter() - t0) * 1e6)
        scores.append(g["total"])
        hors_bornes += 0 if 0 <= g["total"] <= 100 else 1
        provisoires += 1 if g["provisoire"] else 0
    grille = {"profils": len(scores), "resultats_distincts_pour_2000_repetitions_du_meme_dossier": len(repetitions),
              "ecart_type_sur_repetitions": 0.0 if len(repetitions) == 1 else None,
              "scores_hors_de_0_a_100": hors_bornes, "scores_provisoires": provisoires,
              "score_moyen": round(statistics.mean(scores), 1), "score_min": min(scores), "score_max": max(scores),
              "duree_microsecondes": stats_ms(durees_grille), "statut_parametres": PARAMETRES.resume()["statut"],
              "empreinte_parametres": PARAMETRES.empreinte}
    print("grille :", grille)

    synthese = {"calcul_mensualite": calcul, "moteur": moteur, "grille_de_score": grille}
    detail = []

    # (c) De bout en bout, avec le modèle de langage, sur le dossier fictif
    if not sans_llm:
        textes = {}
        service, _ = charger_ocr()
        for type_doc in CHAMPS_ATTENDUS:
            service._cache.clear()
            textes[type_doc] = service.extraire(str(DOSSIER_TEST / "natifs" / f"{type_doc}.pdf")).get("texte", "")
        document = ("=== DEMANDE DE CREDIT (formulaire client) ===\nMontant demande: 9000 TND\nDuree souhaitee: 48 mois\n\n"
                    + "".join(f"=== {t} ({t.lower()}.pdf) ===\n{txt}\n\n" for t, txt in textes.items()))
        attendu_mens = mensualite_reference(9000, 48, 0.10)
        attendu_dti = (attendu_mens + 250) / 2100 * 100
        sorties = []
        for i in range(runs):
            AgentService._cache.clear()
            agent = AgentService()
            t0 = time.time()
            res = agent.analyser_consommation(document)
            duree = (time.time() - t0) * 1000
            fm = res.get("financialMetrics") or {}
            sorties.append({"run": i + 1, "duree_ms": round(duree), "statut": res.get("statut"),
                            "decision": res.get("eligibility"), "score": res.get("eligibilityScore"),
                            "score_llm_indicatif": res.get("scoreLLMIndicatif"),
                            "revenu": fm.get("monthlyIncome"), "dettes": fm.get("existingDebts"),
                            "mensualite": fm.get("monthlyPayment"), "dti": fm.get("dti"),
                            "provider": res.get("provider"), "donnees_manquantes": res.get("donneesManquantes")})
            print(sorties[-1])
        valides = [s for s in sorties if s["statut"] == "SUCCESS"]
        synthese["bout_en_bout"] = {
            "runs": runs, "reussis": len(valides),
            "duree_ms": stats_ms([s["duree_ms"] for s in valides]),
            "decisions": dict(Counter(s["decision"] for s in valides)),
            "scores": sorted(s["score"] for s in valides),
            "ecart_type_score": round(statistics.pstdev([s["score"] for s in valides]), 2) if len(valides) > 1 else None,
            "revenu_exact": f"{sum(1 for s in valides if s['revenu'] == 2100)}/{len(valides)}",
            "dettes_exactes": f"{sum(1 for s in valides if s['dettes'] == 250)}/{len(valides)}",
            "mensualite_attendue": round(attendu_mens, 3), "dti_attendu": round(attendu_dti, 2),
            "dti_exact": f"{sum(1 for s in valides if s['dti'] is not None and abs(s['dti'] - attendu_dti) < 0.05)}/{len(valides)}",
        }
        detail = sorties
    print(json.dumps(synthese, ensure_ascii=False, indent=2))
    return {"detail": detail, "synthese": synthese}


# ── Entrée ───────────────────────────────────────────────────────────────────

def main():
    parseur = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parseur.add_argument("section", choices=["ocr", "classification", "rag", "decision", "all"])
    parseur.add_argument("--sans-llm", action="store_true")
    parseur.add_argument("--runs", type=int, default=5)
    args = parseur.parse_args()

    sections = ["ocr", "classification", "rag", "decision"] if args.section == "all" else [args.section]
    for s in sections:
        if s == "ocr":
            ecrire("ocr", mesurer_ocr())
        elif s == "classification":
            ecrire("classification", mesurer_classification(args.sans_llm))
        elif s == "rag":
            ecrire("rag", mesurer_rag(args.sans_llm))
        elif s == "decision":
            ecrire("decision", mesurer_decision(args.sans_llm, args.runs))


if __name__ == "__main__":
    main()
