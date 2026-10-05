# tests/test_pipeline_complete.py
"""
Évaluation de bout en bout de la pipeline CrediSense :

    PDF → extraction du texte (natif ou OCR) → nettoyage → embeddings → cascade → catégorie

- L'extraction passe par la VRAIE route /ocr du serveur (comme l'application).
  Le format de la requête est découvert automatiquement via /openapi.json.
- La classification utilise la VRAIE cascade (services/document_classifier_service.py),
  y compris les appels au modèle de langage pour les documents en zone grise.

Résultats :
  - une ligne par document ;
  - Tableau 4.5 : indicateurs de l'extraction, PDF natif vs PDF scanné ;
  - indicateurs de classification (embeddings seuls vs cascade) ;
  - exactitude de bout en bout + étape responsable de chaque erreur ;
  - CSV et matrice de confusion dans tests/resultats/.

Prérequis :
  1. Documents de test dans tests/documents/  (python tests/generer_documents_test.py)
  2. Serveur lancé dans un AUTRE terminal :  python main.py
  3. Puis, depuis Python\\ (venv activé) :   python tests/test_pipeline_complete.py

Options :
  --url http://localhost:8002     adresse du serveur
  --montrer-reponse               affiche la réponse brute de /ocr (diagnostic)
"""

import argparse
import csv
import json
import statistics
import sys
import time
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

import httpx

DOSSIER_TESTS = Path(__file__).resolve().parent
RACINE        = DOSSIER_TESTS.parent
sys.path.insert(0, str(RACINE))

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

CATEGORIES = [
    "CIN", "FICHE_PAIE", "RELEVE_BANCAIRE", "ATTESTATION_EMPLOI",
    "CONTRAT_TRAVAIL", "ASSURANCE_VIE", "BILAN_COMPTABLE",
    "DECLARATION_FISCALE", "JUSTIFICATIF_DOMICILE", "TITRE_SEJOUR", "AUTRE",
]

CLES_TEXTE     = ["texte_nettoye", "texte_propre", "cleaned_text", "clean_text", "texte",
                  "text", "texte_brut", "raw_text", "texte_ocr", "ocr_text", "contenu", "content"]
CLES_CONFIANCE = ["confiance_ocr", "ocr_confidence", "confiance_moyenne", "mean_confidence",
                  "confidence", "confiance", "score_ocr"]
CLES_METHODE   = ["methode", "method", "moteur", "engine", "mode", "type_extraction",
                  "extraction_method", "source"]
CLES_OCR_BOOL  = ["ocr_utilise", "ocr_used", "is_scanned", "scanne", "scanned"]
CLES_PAGES     = ["nb_pages", "nombre_pages", "page_count", "pages", "num_pages"]
CLES_CLASSE    = ["type_document", "document_type", "categorie", "category", "classification"]


# ── Utilitaires ───────────────────────────────────────────────────────────────
def fmt(x, d=1):
    return f"{x:.{d}f}".replace(".", ",")


def pct(a, b):
    return f"{fmt(a / b * 100)} %" if b else "–"


def chercher(obj, cles, test=lambda v: v not in (None, "", [], {})):
    """Cherche récursivement la 1re clé (par ordre de priorité) dont la valeur passe le test."""
    def parcourir(o, cle):
        if isinstance(o, dict):
            for k, v in o.items():
                if k.lower() == cle and test(v):
                    return v
            for v in o.values():
                r = parcourir(v, cle)
                if r is not None:
                    return r
        elif isinstance(o, list):
            for v in o:
                r = parcourir(v, cle)
                if r is not None:
                    return r
        return None
    for cle in cles:
        r = parcourir(obj, cle)
        if r is not None:
            return r
    return None


def infos_pdf(chemin: Path):
    """(nombre de pages, a une couche texte ?) — None si aucune librairie dispo."""
    for module in ("pypdf", "PyPDF2"):
        try:
            lib = __import__(module)
            lecteur = lib.PdfReader(str(chemin))
            texte = "".join((p.extract_text() or "") for p in lecteur.pages)
            return len(lecteur.pages), len(texte.strip()) >= 20
        except ImportError:
            continue
        except Exception:
            break
    try:
        import fitz
        doc = fitz.open(str(chemin))
        texte = "".join(p.get_text() for p in doc)
        return doc.page_count, len(texte.strip()) >= 20
    except Exception:
        return None, None


# ── Découverte de la route /ocr ───────────────────────────────────────────────
def decouvrir_route_ocr(client: httpx.Client):
    spec = client.get("/openapi.json").json()
    schemas = spec.get("components", {}).get("schemas", {})

    def resoudre(s):
        while isinstance(s, dict) and "$ref" in s:
            s = schemas.get(s["$ref"].split("/")[-1], {})
        return s or {}

    candidates = [(p, ops["post"]) for p, ops in spec.get("paths", {}).items()
                  if "post" in ops and "ocr" in p.lower()]
    if not candidates:
        raise RuntimeError("Aucune route POST contenant 'ocr' dans /openapi.json")
    candidates.sort(key=lambda c: (c[0] != "/ocr", len(c[0])))
    chemin, op = candidates[0]

    champ_fichier, champs_form, params_query = "file", {}, {}
    contenu = op.get("requestBody", {}).get("content", {})
    schema = resoudre(contenu.get("multipart/form-data", {}).get("schema", {}))
    requis = set(schema.get("required", []))
    for nom, prop in schema.get("properties", {}).items():
        prop = resoudre(prop)
        est_fichier = prop.get("format") == "binary" or (
            prop.get("type") == "array" and resoudre(prop.get("items", {})).get("format") == "binary")
        if est_fichier:
            champ_fichier = nom
        elif nom in requis:
            champs_form[nom] = valeur_test(nom)
    for param in op.get("parameters", []):
        if param.get("required") and param.get("in") == "query":
            params_query[param["name"]] = valeur_test(param["name"])
    return chemin, champ_fichier, champs_form, params_query


def valeur_test(nom: str) -> str:
    n = nom.lower()
    if "cin" in n:
        return "00000000"
    if "dossier" in n or n.endswith("id"):
        return "test-pipeline"
    return "TEST"


# ── Programme principal ───────────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8002")
    parser.add_argument("--dossier", default=str(DOSSIER_TESTS / "documents"))
    parser.add_argument("--montrer-reponse", action="store_true")
    args = parser.parse_args()

    print("=" * 118)
    print(" CrediSense — Évaluation de bout en bout : PDF → extraction / OCR → classification")
    print(f" {datetime.now():%d/%m/%Y %H:%M}   —   serveur : {args.url}")
    print("=" * 118)

    fichiers = sorted(Path(args.dossier).glob("*.pdf"))
    fichiers = [f for f in fichiers if f.stem.split("__")[0].upper() in CATEGORIES]
    if not fichiers:
        print(f"Aucun PDF nommé <CATEGORIE>__xxx.pdf dans {args.dossier}")
        print("→ lancez d'abord : python tests/generer_documents_test.py")
        return

    client = httpx.Client(base_url=args.url, timeout=httpx.Timeout(600.0, connect=5.0))
    try:
        route, champ_fichier, champs_form, params_query = decouvrir_route_ocr(client)
    except httpx.ConnectError:
        print(f"\n✗ Serveur injoignable sur {args.url}. Lancez-le dans un autre terminal : python main.py")
        return
    print(f"\nRoute d'extraction : POST {route}  (fichier : '{champ_fichier}'"
          f"{', champs : ' + str(champs_form) if champs_form else ''}"
          f"{', paramètres : ' + str(params_query) if params_query else ''})")

    print("Chargement de la cascade de classification…")
    from services.document_classifier_service import (
        DocumentClassifierService, SEUIL_CONFIANCE_ELEVEE, SEUIL_CONFIANCE_BASSE,
    )
    classifieur = DocumentClassifierService()
    classifieur.embeddings_classifier.classify("préchauffage du modèle", None)
    print(f"{len(fichiers)} documents à traiter.\n")

    lignes = []
    entete = (f"{'Document':<38}{'Type':<8}{'Pg':>3}{'Extr. ms':>9}{'Conf.OCR':>9}{'Car.':>6}  "
              f"{'Embeddings':<22}{'Méthode':<15}{'Final':<22}{'Clas. ms':>9}  OK")
    print(entete)
    print("-" * len(entete))

    for i, fichier in enumerate(fichiers):
        attendu = fichier.stem.split("__")[0].upper()
        pages_pdf, couche_texte = infos_pdf(fichier)

        # ── 1. Extraction via le serveur ─────────────────────────────────────
        t0 = time.perf_counter()
        erreur_extraction, donnees = None, {}
        try:
            with open(fichier, "rb") as f:
                rep = client.post(route, files={champ_fichier: (fichier.name, f, "application/pdf")},
                                  data=champs_form, params=params_query)
            duree_extraction = (time.perf_counter() - t0) * 1000
            if rep.status_code != 200:
                erreur_extraction = f"HTTP {rep.status_code}"
            else:
                donnees = rep.json()
        except Exception as e:
            duree_extraction = (time.perf_counter() - t0) * 1000
            erreur_extraction = type(e).__name__

        if args.montrer_reponse and i == 0:
            print("\n── Réponse brute de", route, "(premier document) ──")
            print(json.dumps(donnees, ensure_ascii=False, indent=2)[:2500])
            print("─" * 60 + "\n")

        texte = chercher(donnees, CLES_TEXTE, test=lambda v: isinstance(v, str) and v.strip() != "")
        texte = texte if isinstance(texte, str) else ""
        conf_ocr = chercher(donnees, CLES_CONFIANCE, test=lambda v: isinstance(v, (int, float)))
        if isinstance(conf_ocr, (int, float)) and conf_ocr > 1:
            conf_ocr = conf_ocr / 100
        methode_srv = str(chercher(donnees, CLES_METHODE, test=lambda v: isinstance(v, str)) or "")
        ocr_bool = chercher(donnees, CLES_OCR_BOOL, test=lambda v: isinstance(v, bool))
        pages_srv = chercher(donnees, CLES_PAGES, test=lambda v: isinstance(v, (int, list)))
        pages = pages_pdf or (len(pages_srv) if isinstance(pages_srv, list) else pages_srv) or 1

        # Natif / scanné : couche texte du PDF, sinon indice dans la réponse, sinon nom du fichier
        if couche_texte is not None:
            natif = couche_texte
        elif isinstance(ocr_bool, bool):
            natif = not ocr_bool
        elif methode_srv:
            natif = "ocr" not in methode_srv.lower()
        else:
            natif = "scan" not in fichier.stem.lower()
        # OCR réellement utilisé par le serveur (si l'information est disponible)
        if isinstance(ocr_bool, bool):
            ocr_utilise = ocr_bool
        elif methode_srv:
            ocr_utilise = "ocr" in methode_srv.lower() or "paddle" in methode_srv.lower()
        else:
            ocr_utilise = not natif

        extraction_ok = erreur_extraction is None and len(texte.strip()) >= 20
        if erreur_extraction is None and not extraction_ok:
            erreur_extraction = "texte vide"

        # ── 2. Classification (vraie cascade) ────────────────────────────────
        pred_emb = pred_final = "—"
        conf_emb, methode_cls, duree_cls, appel_llm = None, "—", 0.0, False
        if extraction_ok:
            emb = classifieur.embeddings_classifier.classify(texte, None)
            t1 = time.perf_counter()
            final = classifieur.classify(texte, None)
            duree_cls = (time.perf_counter() - t1) * 1000
            methode_cls = final.get("methode", "?")
            conf_emb = float(final.get("confiance_embeddings_initiale", emb.get("confiance", 0.0)) or 0.0)
            pred_emb = str(emb.get("type_document") or emb.get("categorie") or "AUTRE").upper()
            pred_final = str(final.get("type_document") or final.get("categorie") or "AUTRE").upper()
            if methode_cls == "embeddings_faible_confiance":
                pred_final = "AUTRE"
            if conf_emb < SEUIL_CONFIANCE_BASSE:
                pred_emb = "AUTRE"
            appel_llm = methode_cls in ("llm_fallback", "embeddings_llm_indisponible")

        correct = extraction_ok and pred_final == attendu
        etape_erreur = ("" if correct else
                        "extraction" if not extraction_ok else "classification")

        lignes.append({
            "document": fichier.name, "attendu": attendu, "type_pdf": "natif" if natif else "scanné",
            "pages": pages, "ocr_utilise": ocr_utilise, "methode_extraction": methode_srv,
            "extraction_ms": round(duree_extraction, 1),
            "confiance_ocr": round(conf_ocr, 4) if isinstance(conf_ocr, (int, float)) else None,
            "nb_caracteres": len(texte), "extraction_ok": extraction_ok,
            "erreur_extraction": erreur_extraction or "",
            "predit_embeddings": pred_emb,
            "confiance_embeddings": round(conf_emb, 4) if conf_emb is not None else None,
            "methode_classification": methode_cls, "appel_llm": appel_llm,
            "predit_final": pred_final, "classification_ms": round(duree_cls, 1),
            "correct": correct, "etape_erreur": etape_erreur,
        })
        print(f"{fichier.name[:37]:<38}{('natif' if natif else 'scanné'):<8}{pages:>3}"
              f"{duree_extraction:>9.0f}"
              f"{(fmt(conf_ocr, 2) if isinstance(conf_ocr, (int, float)) else '–'):>9}"
              f"{len(texte):>6}  {pred_emb[:21]:<22}{methode_cls[:14]:<15}{pred_final[:21]:<22}"
              f"{duree_cls:>9.0f}  {'✓' if correct else '✗'}")

        if i == 0 and not texte and not args.montrer_reponse:
            print("\n⚠ Aucun texte trouvé dans la réponse de /ocr. Relancez avec --montrer-reponse "
                  "et envoyez la réponse affichée.\n")

    # ── 3. Tableau 4.5 : extraction ──────────────────────────────────────────
    natifs  = [l for l in lignes if l["type_pdf"] == "natif"]
    scannes = [l for l in lignes if l["type_pdf"] == "scanné"]

    def t_moyen(g):
        return statistics.mean(l["extraction_ms"] for l in g) if g else None

    def t_page(g):
        return sum(l["extraction_ms"] for l in g) / sum(l["pages"] for l in g) if g else None

    def succes(g):
        return sum(l["extraction_ok"] for l in g) / len(g) * 100 if g else None

    def conf(g):
        v = [l["confiance_ocr"] for l in g if l["confiance_ocr"] is not None]
        return statistics.mean(v) if v else None

    def cel(v, d=1, suffixe=""):
        return "–" if v is None else fmt(v, d) + suffixe

    print("\n" + "=" * 118)
    print(" TABLEAU 4.5 — INDICATEURS DU MODULE D'EXTRACTION")
    print("=" * 118)
    print(f"   {'Indicateur':<40}{'PDF natif':>14}{'PDF scanné':>14}")
    print(f"   {'Nombre de documents testés':<40}{len(natifs):>14}{len(scannes):>14}")
    print(f"   {'Temps moyen de traitement (ms)':<40}{cel(t_moyen(natifs), 0):>14}{cel(t_moyen(scannes), 0):>14}")
    print(f"   {'Temps moyen par page (ms)':<40}{cel(t_page(natifs), 0):>14}{cel(t_page(scannes), 0):>14}")
    print(f"   {'Taux de traitements réussis (%)':<40}{cel(succes(natifs)):>14}{cel(succes(scannes)):>14}")
    print(f"   {'Confiance OCR moyenne':<40}{cel(conf(natifs), 2):>14}{cel(conf(scannes), 2):>14}")
    evites = sum(1 for l in lignes if not l["ocr_utilise"])
    print(f"   Taux d'évitement OCR : {evites}/{len(lignes)}  ({pct(evites, len(lignes))})"
          f"   — documents traités sans OCR")
    if all(l["confiance_ocr"] is None for l in lignes):
        print("   (confiance OCR non renvoyée par /ocr : relancez avec --montrer-reponse pour vérifier)")

    # ── 4. Classification ────────────────────────────────────────────────────
    extraits = [l for l in lignes if l["extraction_ok"]]
    n = len(lignes)
    print("\n" + "=" * 118)
    print(" CLASSIFICATION ET BOUT EN BOUT")
    print("=" * 118)
    if extraits:
        ok_emb = sum(l["predit_embeddings"] == l["attendu"] for l in extraits)
        ok_cas = sum(l["predit_final"] == l["attendu"] for l in extraits)
        llm = sum(l["appel_llm"] for l in extraits)
        zones = Counter(l["methode_classification"] for l in extraits)
        print(f" Documents classifiés (extraction réussie)          : {len(extraits)}/{n}")
        print(f" Exactitude — embeddings seuls                      : {ok_emb}/{len(extraits)}  ({pct(ok_emb, len(extraits))})")
        print(f" Exactitude — cascade complète                      : {ok_cas}/{len(extraits)}  ({pct(ok_cas, len(extraits))})")
        print(f" Décisions locales (sans LLM)                       : {len(extraits) - llm}/{len(extraits)}  ({pct(len(extraits) - llm, len(extraits))})")
        print(f"   décision directe : {zones['embeddings']}   non identifiable : {zones['embeddings_faible_confiance']}"
              f"   zone grise → LLM : {zones['llm_fallback'] + zones['embeddings_llm_indisponible']}"
              f"{'  (dont LLM indisponible : ' + str(zones['embeddings_llm_indisponible']) + ')' if zones['embeddings_llm_indisponible'] else ''}")
        print(f" Temps moyen de classification                      : {fmt(statistics.mean(l['classification_ms'] for l in extraits))} ms")
    for nom, groupe in (("natifs", natifs), ("scannés", scannes)):
        if groupe:
            ok = sum(l["correct"] for l in groupe)
            print(f" Exactitude de bout en bout — PDF {nom:<8}          : {ok}/{len(groupe)}  ({pct(ok, len(groupe))})")
    ok_total = sum(l["correct"] for l in lignes)
    print(f" EXACTITUDE DE BOUT EN BOUT (PDF → catégorie)       : {ok_total}/{n}  ({pct(ok_total, n)})")
    print(f" Temps moyen total par document                     : "
          f"{fmt(statistics.mean(l['extraction_ms'] + l['classification_ms'] for l in lignes), 0)} ms")

    erreurs = [l for l in lignes if not l["correct"]]
    if erreurs:
        print("\n Erreurs (avec l'étape responsable) :")
        for l in erreurs:
            detail = (l["erreur_extraction"] if l["etape_erreur"] == "extraction"
                      else f"prédit {l['predit_final']} ({l['methode_classification']})")
            print(f"   {l['document']:<40} étape : {l['etape_erreur']:<15} {detail}")

    # ── 5. Export ────────────────────────────────────────────────────────────
    dossier_res = DOSSIER_TESTS / "resultats"
    dossier_res.mkdir(exist_ok=True)
    horodatage = datetime.now().strftime("%Y%m%d_%H%M")
    chemin_csv = dossier_res / f"pipeline_{horodatage}.csv"
    with open(chemin_csv, "w", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=list(lignes[0].keys()), delimiter=";")
        writer.writeheader()
        writer.writerows(lignes)
    print(f"\n Détail enregistré : {chemin_csv}")

    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt
        presentes = [c for c in CATEGORIES
                     if any(l["attendu"] == c or l["predit_final"] == c for l in lignes)]
        if any(l["predit_final"] == "—" for l in lignes):
            presentes.append("—")
        matrice = defaultdict(int)
        for l in lignes:
            matrice[(l["attendu"], l["predit_final"])] += 1
        lignes_m = [c for c in presentes if c != "—"]
        donnees = [[matrice[(a, p)] for p in presentes] for a in lignes_m]
        fig, ax = plt.subplots(figsize=(8, 7))
        ax.imshow(donnees, cmap="Oranges")
        ax.set_xticks(range(len(presentes)),
                      labels=[("ÉCHEC EXTRACTION" if p == "—" else p) for p in presentes],
                      rotation=45, ha="right", fontsize=8)
        ax.set_yticks(range(len(lignes_m)), labels=lignes_m, fontsize=8)
        for i, ligne in enumerate(donnees):
            for j, v in enumerate(ligne):
                if v:
                    ax.text(j, i, v, ha="center", va="center", fontsize=9)
        ax.set_xlabel("Catégorie prédite (fin de pipeline)")
        ax.set_ylabel("Catégorie attendue")
        ax.set_title("Matrice de confusion — pipeline complète")
        fig.tight_layout()
        chemin_png = dossier_res / f"pipeline_matrice_{horodatage}.png"
        fig.savefig(chemin_png, dpi=150)
        print(f" Matrice de confusion (image) : {chemin_png}")
    except ImportError:
        print(" (matplotlib absent : pas d'image de matrice de confusion)")
    print("=" * 118)


if __name__ == "__main__":
    main()