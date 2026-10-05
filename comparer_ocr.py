"""
benchmark_ocr.py — Benchmark OCR sur plusieurs documents, résumé dans UN tableau

Moteurs : docTR (latin), PaddleOCR français, PaddleOCR arabe
Sans vérité terrain : on compte les mots arabes, français et nombres lus avec
une confiance ≥ 80 %, et on mesure le temps.

Usage :
    python benchmark_ocr.py document_fr.pdf document_ar.pdf

    (optionnel, si vous avez tapé le texte exact d'un document :)
    python benchmark_ocr.py --doc document_fr.pdf verite_fr.txt --doc document_ar.pdf verite_ar.txt

Options :
    --runs 2     passages OCR par moteur (le 1er inclut l'initialisation)
    --mkldnn     active l'accélération CPU de PaddleOCR

Résultats :
    benchmark_ocr.html  → tableau de décision + conclusion + détails par document
    benchmark_ocr.csv   → tableau de décision (Excel)
    benchmark_ocr.json  → tout (mesures + textes)
"""

# ── Stub weasyprint : doctr l'importe, mais il exige GTK3 sous Windows ────
import sys
import types

if "weasyprint" not in sys.modules:
    _fake = types.ModuleType("weasyprint")
    _fake.HTML = None
    sys.modules["weasyprint"] = _fake
# ──────────────────────────────────────────────────────────────────────────

import os

os.environ.setdefault("DISABLE_MODEL_SOURCE_CHECK", "True")

import argparse
import csv
import html
import json
import platform
import re
import statistics
import subprocess
import tempfile
import time
import traceback
import unicodedata
import webbrowser
from collections import Counter
from datetime import datetime
from importlib import metadata
from pathlib import Path

try:
    import pymupdf as fitz
except ImportError:
    import fitz

try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except Exception:
    pass

DPI = 300
TIMEOUT_MOTEUR = 3600
DETECTION_PADDLE = "PP-OCRv5_mobile_det"

MOTEURS = {
    "doctr_latin": {"nom": "docTR (latin)",      "type": "doctr"},
    "paddle_fr":   {"nom": "PaddleOCR français", "type": "paddle", "modele": "latin_PP-OCRv5_mobile_rec"},
    "paddle_ar":   {"nom": "PaddleOCR arabe",    "type": "paddle", "modele": "arabic_PP-OCRv5_mobile_rec"},
}

IMAGES_ACCEPTEES = {".png", ".jpg", ".jpeg", ".bmp", ".tif", ".tiff"}
CHIFFRES_ARABES = str.maketrans("٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹", "01234567890123456789")
_HARAKAT_TATWEEL = re.compile(r"[ً-ْٰـ]")
_ALEFS = re.compile(r"[أإآ]")
_PLAGES_ARABES = ((0x0600, 0x06FF), (0x0750, 0x077F), (0x08A0, 0x08FF),
                  (0xFB50, 0xFDFF), (0xFE70, 0xFEFF))
CODES_CRASH_WINDOWS = {
    0xC0000005: "violation d'accès mémoire (souvent un conflit de DLL)",
    0xC0000409: "arrêt forcé d'une librairie native",
    0xC0000017: "mémoire insuffisante",
}


# ═════════════════════════════════════════════════════════════════════════
# Préparation
# ═════════════════════════════════════════════════════════════════════════

def preparer_images(fichier: Path, dossier: Path) -> list[str]:
    """PDF → une image PNG par page (300 DPI). Une image est utilisée telle quelle."""
    if fichier.suffix.lower() in IMAGES_ACCEPTEES:
        return [str(fichier.resolve())]
    dossier.mkdir(parents=True, exist_ok=True)
    chemins = []
    with fitz.open(str(fichier)) as doc:
        for i, page in enumerate(doc):
            pix = page.get_pixmap(dpi=DPI, colorspace=fitz.csRGB, alpha=False)
            chemin = dossier / f"page_{i + 1}.png"
            pix.save(str(chemin))
            chemins.append(str(chemin))
    return chemins


# ═════════════════════════════════════════════════════════════════════════
# Processus enfant : UN moteur, TOUS les documents (modèle chargé une fois)
# ═════════════════════════════════════════════════════════════════════════

def est_arabe(texte: str) -> bool:
    arabes = latines = 0
    for c in texte:
        if any(a <= ord(c) <= b for a, b in _PLAGES_ARABES):
            arabes += 1
        elif c.isalpha():
            latines += 1
    return arabes > latines


def regrouper_par_ligne(zones: list[dict]) -> list[str]:
    """Zones à la même hauteur = une ligne ; ordre droite→gauche si ligne arabe."""
    if not zones:
        return []
    hauteur_med = statistics.median(z["y2"] - z["y1"] for z in zones) or 1.0
    centre = lambda z: (z["y1"] + z["y2"]) / 2
    groupes = []
    for z in sorted(zones, key=centre):
        if groupes:
            c = sum(centre(g) for g in groupes[-1]) / len(groupes[-1])
            if abs(centre(z) - c) <= hauteur_med * 0.5:
                groupes[-1].append(z)
                continue
        groupes.append([z])
    return [" ".join(z["texte"] for z in sorted(g, key=lambda z: z["x1"],
                                                 reverse=est_arabe(" ".join(z["texte"] for z in g))))
            for g in groupes]


def charger_moteur(cfg: dict, mkldnn: bool):
    if cfg["type"] == "doctr":
        from doctr.models import ocr_predictor
        return ocr_predictor(pretrained=True)
    from paddleocr import PaddleOCR
    return PaddleOCR(
        text_detection_model_name=DETECTION_PADDLE,
        text_recognition_model_name=cfg["modele"],
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
        use_textline_orientation=False,
        enable_mkldnn=mkldnn,
    )


def executer_moteur(cfg: dict, moteur, images: list[str]):
    """
    Un passage OCR complet sur un document.
    Renvoie (lignes, confiances, mots_scores) — mots_scores = [[mot, confiance], …].
    """
    lignes, confs, mots_scores = [], [], []
    if cfg["type"] == "doctr":
        from doctr.io import DocumentFile
        result = moteur(DocumentFile.from_images(images))
        for page in result.pages:
            for block in page.blocks:
                for line in block.lines:
                    if line.words:
                        valeurs = [unicodedata.normalize("NFKC", w.value) for w in line.words]
                        lignes.append(" ".join(valeurs))
                        confs.extend(float(w.confidence) for w in line.words)
                        mots_scores += [[v, float(w.confidence)] for v, w in zip(valeurs, line.words)]
        return lignes, confs, mots_scores

    import numpy as np
    for img in images:
        for res in moteur.predict(img):
            try:
                boites = [[float(v) for v in b] for b in res["rec_boxes"]]
            except (KeyError, TypeError):
                boites = []
                for poly in res["rec_polys"]:
                    p = np.asarray(poly, dtype=float)
                    boites.append([p[:, 0].min(), p[:, 1].min(), p[:, 0].max(), p[:, 1].max()])
            zones = []
            for t, s, (x1, y1, x2, y2) in zip(res["rec_texts"], res["rec_scores"], boites):
                t = unicodedata.normalize("NFKC", str(t)).strip()
                if t:
                    zones.append({"texte": t, "x1": x1, "y1": y1, "x2": x2, "y2": y2})
                    confs.append(float(s))
                    mots_scores += [[m, float(s)] for m in t.split()]
            lignes.extend(regrouper_par_ligne(zones))
    return lignes, confs, mots_scores


def worker(config_path: str):
    config = json.loads(Path(config_path).read_text(encoding="utf-8"))
    cfg = MOTEURS[config["cle"]]
    try:
        t0 = time.perf_counter()
        moteur = charger_moteur(cfg, config["mkldnn"])
        chargement = time.perf_counter() - t0

        par_doc = []
        for i, images in enumerate(config["documents"]):
            temps, lignes, confs, mots_scores = [], [], [], []
            for run in range(config["runs"]):
                print(f"   document {i + 1} — passage {run + 1}/{config['runs']}…", flush=True)
                t0 = time.perf_counter()
                lignes, confs, mots_scores = executer_moteur(cfg, moteur, images)
                temps.append(time.perf_counter() - t0)
            par_doc.append({"lignes": lignes, "temps": temps, "mots_scores": mots_scores,
                            "confiance": sum(confs) / len(confs) if confs else 0.0})
        r = {"ok": True, "chargement": chargement, "par_doc": par_doc}
    except Exception as e:
        r = {"ok": False, "erreur": f"{e}\n\n{traceback.format_exc()}"}
    Path(config["sortie"]).write_text(json.dumps(r, ensure_ascii=False), encoding="utf-8")


def lancer_moteur(cle: str, documents: list[list[str]], dossier: str,
                  runs: int, mkldnn: bool) -> dict:
    sortie = Path(dossier) / f"resultat_{cle}.json"
    config = Path(dossier) / f"config_{cle}.json"
    config.write_text(json.dumps({"cle": cle, "sortie": str(sortie), "documents": documents,
                                  "runs": runs, "mkldnn": mkldnn}), encoding="utf-8")
    try:
        proc = subprocess.run([sys.executable, str(Path(__file__).resolve()),
                               "--worker", str(config)], timeout=TIMEOUT_MOTEUR)
    except subprocess.TimeoutExpired:
        return {"ok": False, "erreur": f"Temps dépassé ({TIMEOUT_MOTEUR}s)."}
    if sortie.exists():
        return json.loads(sortie.read_text(encoding="utf-8"))
    code = proc.returncode & 0xFFFFFFFF
    return {"ok": False, "erreur": f"Crash du processus — code 0x{code:08X} "
                                   f"({CODES_CRASH_WINDOWS.get(code, 'crash natif')})"}


# ═════════════════════════════════════════════════════════════════════════
# Mesures
# ═════════════════════════════════════════════════════════════════════════

def normaliser_eval(texte: str) -> str:
    """NFKC, chiffres arabes → latins, sans harakat ni tatweel, alefs unifiés, espaces simples."""
    t = unicodedata.normalize("NFKC", texte).translate(CHIFFRES_ARABES)
    t = _ALEFS.sub("ا", _HARAKAT_TATWEEL.sub("", t))
    return re.sub(r"\s+", " ", t).strip()


def mots(texte: str) -> list[str]:
    return re.findall(r"\w+", normaliser_eval(texte))


def levenshtein(a, b) -> int:
    try:
        from rapidfuzz.distance import Levenshtein
        return Levenshtein.distance(a, b)
    except ImportError:
        prec = list(range(len(b) + 1))
        for i, x in enumerate(a, 1):
            cour = [i]
            for j, y in enumerate(b, 1):
                cour.append(min(prec[j] + 1, cour[j - 1] + 1, prec[j - 1] + (x != y)))
            prec = cour
        return prec[-1]


def calculer_mesures(sortie: str, verite: str) -> dict:
    ref, hyp = normaliser_eval(verite), normaliser_eval(sortie)
    m_ref, m_hyp = mots(verite), mots(sortie)
    c_ref, c_hyp = Counter(m_ref), Counter(m_hyp)
    communs = sum((c_ref & c_hyp).values())
    rappel = communs / len(m_ref) if m_ref else 0.0
    precision = communs / len(m_hyp) if m_hyp else 0.0
    n_ref, n_hyp = Counter(re.findall(r"\d{2,}", ref)), Counter(re.findall(r"\d{2,}", hyp))
    return {
        "cer":         levenshtein(hyp, ref) / len(ref) if ref else None,
        "rappel":      rappel,
        "precision":   precision,
        "f1":          2 * rappel * precision / (rappel + precision) if rappel + precision else 0.0,
        "nombres_ok":  sum((n_ref & n_hyp).values()),
        "nombres_tot": sum(n_ref.values()),
    }


# ═════════════════════════════════════════════════════════════════════════
# Tableau de décision + conclusion
# ═════════════════════════════════════════════════════════════════════════

SEUIL_MOT_FIABLE = 0.80   # sans vérité terrain : on ne compte que les mots lus avec ≥ 80 %


def compter_lecture(mots_scores: list) -> dict:
    """Sans vérité terrain : compte les mots arabes, français et nombres lus avec confiance."""
    arabes = latins = nombres = 0
    for mot, score in mots_scores:
        if score < SEUIL_MOT_FIABLE:
            continue
        for m in mots(mot):
            if m.isdigit():
                nombres += len(m) >= 2
            elif est_arabe(m):
                arabes += 1
            elif len(m) >= 2:
                latins += 1
    return {"arabes": arabes, "latins": latins, "nombres": nombres}


def colonnes_document(d: dict) -> list[tuple]:
    """(libellé, valeur(pd) pour trouver le meilleur, affichage(pd), sens)."""
    temps = ("Temps (s)", lambda pd: pd["median"], lambda pd: f"{pd['median']:.1f}", "bas")
    if d["verite"]:
        nb = lambda pd: pd["mesures"]
        return [
            ("F1", lambda pd: nb(pd)["f1"], lambda pd: f"{nb(pd)['f1']:.0%}", "haut"),
            ("Nombres", lambda pd: nb(pd)["nombres_ok"] / nb(pd)["nombres_tot"] if nb(pd)["nombres_tot"] else None,
             lambda pd: f"{nb(pd)['nombres_ok']}/{nb(pd)['nombres_tot']}", "haut"),
            temps,
        ]
    c = lambda pd: pd["lecture"]
    return [
        ("Mots arabes lus",   lambda pd: c(pd)["arabes"],  lambda pd: str(c(pd)["arabes"]),  "haut"),
        ("Mots français lus", lambda pd: c(pd)["latins"],  lambda pd: str(c(pd)["latins"]),  "haut"),
        ("Nombres lus",       lambda pd: c(pd)["nombres"], lambda pd: str(c(pd)["nombres"]), "haut"),
        temps,
    ]


def tableau_decision(docs: list[dict], resultats: dict) -> tuple[list[str], list[list[str]], set]:
    """Lignes = moteurs ; colonnes = mesures de chaque document (+ F1 moyen si vérités)."""
    avec_f1_moyen = all(d["verite"] for d in docs)
    entetes, specs = ["Moteur"], []
    for i, d in enumerate(docs):
        for lib, val, aff, sens in colonnes_document(d):
            entetes.append(f"{d['nom']} — {lib}")
            specs.append((i, val, aff, sens))
    if avec_f1_moyen:
        entetes.append("F1 moyen")

    lignes, valeurs = [], []
    for cle, r in resultats.items():
        ligne, vals = [MOTEURS[cle]["nom"]], []
        for i, val, aff, sens in specs:
            if not r.get("ok"):
                ligne.append("erreur")
                vals.append(None)
            else:
                pd = r["par_doc"][i]
                ligne.append(aff(pd))
                vals.append((sens, val(pd)))
        if avec_f1_moyen:
            moyen = (sum(pd["mesures"]["f1"] for pd in r["par_doc"]) / len(docs)) if r.get("ok") else None
            ligne.append("erreur" if moyen is None else f"{moyen:.0%}")
            vals.append(("haut", moyen))
        lignes.append(ligne)
        valeurs.append(vals)

    gagnants = set()
    for col in range(len(entetes) - 1):
        colonne = [(i, v[col]) for i, v in enumerate(valeurs) if v[col] and v[col][1] is not None]
        if len(colonne) < 2:
            continue
        sens = colonne[0][1][0]
        cible = (max if sens == "haut" else min)(v[1] for _, v in colonne)
        if sens == "haut" and cible == 0:
            continue  # personne ne lit rien : pas de gagnant
        gagnants |= {(i, col + 1) for i, v in colonne if abs(v[1] - cible) < 1e-9}
    return entetes, lignes, gagnants


def conclusion(docs: list[dict], resultats: dict) -> list[str]:
    ok = {c: r for c, r in resultats.items() if r.get("ok")}
    if not ok:
        return ["Aucun moteur n'a fonctionné : impossible de conclure."]
    nom = lambda c: MOTEURS[c]["nom"]
    phrases = []
    meilleurs_arabe, meilleurs_latin = set(), set()

    for i, d in enumerate(docs):
        pds = {c: r["par_doc"][i] for c, r in ok.items()}
        if d["verite"]:
            cle = max(pds, key=lambda c: pds[c]["mesures"]["f1"])
            phrases.append(f"{d['nom']} : meilleur moteur = {nom(cle)} "
                           f"(F1 {pds[cle]['mesures']['f1']:.0%}).")
            (meilleurs_arabe if est_arabe(d["verite"]) else meilleurs_latin).add(cle)
            continue

        parts = []
        for champ, libelle, cible in (("arabes", "mots arabes", meilleurs_arabe),
                                      ("latins", "mots français", meilleurs_latin),
                                      ("nombres", "nombres", meilleurs_latin)):
            maxi = max(p["lecture"][champ] for p in pds.values())
            if maxi == 0:
                continue
            gagnants = [c for c, p in pds.items() if p["lecture"][champ] == maxi]
            cible.update(gagnants)
            parts.append(f"{' / '.join(nom(c) for c in gagnants)} lit le plus de {libelle} ({maxi})")
        phrases.append(f"{d['nom']} : " + ("; ".join(parts) or "aucun texte lu") + ".")

    if meilleurs_arabe == {"paddle_ar"} and meilleurs_latin <= {"paddle_ar"}:
        phrases.append("PaddleOCR arabe est le meilleur partout : il peut suffire seul.")
    else:
        phrases.append("Aucun modèle n'est le meilleur partout : le modèle arabe est indispensable "
                       "pour l'arabe, un modèle latin pour le français et les chiffres.")
        if "doctr_latin" in meilleurs_latin:
            phrases.append("docTR est écarté malgré ses bons résultats en français : il repose sur "
                           "PyTorch, qui entre en conflit avec PaddlePaddle dans un même processus. "
                           "Le modèle latin de PaddleOCR le remplace.")
        phrases.append("DÉCISION : fusion PaddleOCR arabe + PaddleOCR français — détection une "
                       "seule fois, chaque zone lue par les deux modèles, un seul framework.")

    if not all(d["verite"] for d in docs):
        phrases.append(f"Note : sans vérité terrain, on compte les mots lus avec une confiance "
                       f"≥ {SEUIL_MOT_FIABLE:.0%}, pas les mots justes. Le temps est l'OCR médian, "
                       f"hors chargement du modèle.")
    return phrases


# ═════════════════════════════════════════════════════════════════════════
# Sorties
# ═════════════════════════════════════════════════════════════════════════

def afficher_terminal(entetes, lignes, gagnants, phrases):
    largeurs = [max(len(entetes[c]), *(len(l[c]) + 2 for l in lignes)) + 2 for c in range(len(entetes))]
    sep = "─" * sum(largeurs)
    print("\n" + sep)
    print("".join(e.ljust(w) for e, w in zip(entetes, largeurs)))
    print(sep)
    for i, l in enumerate(lignes):
        print("".join((v + (" ★" if (i, c) in gagnants else "")).ljust(largeurs[c])
                      for c, v in enumerate(l)))
    print(sep + "\n\nCONCLUSION")
    for p in phrases:
        print(f" • {p}")
    print()


def version(p: str) -> str:
    try:
        return metadata.version(p)
    except metadata.PackageNotFoundError:
        return "non installé"


def generer_html(docs, resultats, entetes, lignes, gagnants, phrases, infos) -> str:
    esc = html.escape
    tete = "".join(f"<th>{esc(e)}</th>" for e in entetes)
    corps = "".join(
        "<tr>" + "".join(
            f"<td class='{'best' if (i, c) in gagnants else ''}{' lib' if c == 0 else ''}'>{esc(v)}</td>"
            for c, v in enumerate(l)) + "</tr>"
        for i, l in enumerate(lignes))
    concl = "".join(f"<p class='{'choix' if p.startswith('DÉCISION') else ''}'>{esc(p)}</p>"
                    for p in phrases)

    details = []
    for i, d in enumerate(docs):
        cartes = []
        for cle, r in resultats.items():
            nom = MOTEURS[cle]["nom"]
            if not r.get("ok"):
                cartes.append(f"<div class='carte'><h4>{esc(nom)}</h4><pre class='err'>"
                              f"{esc(r['erreur'])}</pre></div>")
                continue
            pd = r["par_doc"][i]
            m = pd["mesures"]
            meta = (f"CER {m['cer']:.0%} · rappel {m['rappel']:.0%} · précision {m['precision']:.0%} · "
                    if m else "")
            meta += (f"confiance moy. {pd['confiance']:.2f} · 1er passage {pd['temps'][0]:.1f}s · "
                     f"chargement {r['chargement']:.1f}s")
            texte = "".join(f"<div dir='auto'>{esc(l) or '&nbsp;'}</div>" for l in pd["lignes"])
            cartes.append(f"<div class='carte'><h4>{esc(nom)}</h4><div class='meta'>{meta}</div>"
                          f"<div class='texte'>{texte}</div></div>")
        taille = (f" ({d['nb_mots']} mots, {d['nb_nombres']} nombres dans la vérité terrain)"
                  if d["verite"] else "")
        details.append(f"<h3>{esc(d['nom'])} — {esc(d['fichier'])}{esc(taille)}</h3>"
                       f"<div class='grille'>{''.join(cartes)}</div>")

    return f"""<!DOCTYPE html>
<html lang="fr"><head><meta charset="utf-8"><title>Benchmark OCR</title>
<style>
  body {{ font-family: system-ui, sans-serif; margin: 24px; background: #f6f7f9; color: #1d2330; }}
  h1 {{ font-size: 22px; }} h2 {{ font-size: 18px; margin-top: 28px; }} h3 {{ font-size: 15px; margin-top: 22px; }}
  .infos {{ font-size: 12px; color: #4a5263; line-height: 1.6; }}
  table {{ border-collapse: collapse; background: #fff; }}
  th, td {{ border: 1px solid #dde1e8; padding: 8px 12px; font-size: 14px; text-align: center; }}
  th {{ background: #eef1f5; font-weight: 600; }}
  td.lib {{ text-align: left; font-weight: 600; }}
  td.best {{ background: #e3f4e8; font-weight: 700; color: #11632f; }}
  .conclusion {{ background: #fff; border: 1px solid #dde1e8; border-radius: 10px; padding: 6px 18px;
                 max-width: 900px; line-height: 1.55; font-size: 14px; }}
  p.choix {{ background: #e3f4e8; border-left: 4px solid #1f8a4c; padding: 10px 12px;
             font-weight: 700; color: #11632f; }}
  .grille {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 14px; }}
  .carte {{ background: #fff; border: 1px solid #dde1e8; border-radius: 10px; padding: 12px; }}
  .carte h4 {{ margin: 0 0 6px; font-size: 14px; }}
  .meta {{ font-size: 12px; color: #4a5263; margin-bottom: 8px; }}
  .texte {{ font-family: ui-monospace, Consolas, monospace; font-size: 13px; line-height: 1.55;
           background: #fafbfc; border: 1px solid #eceff3; border-radius: 6px; padding: 8px; }}
  pre.err {{ white-space: pre-wrap; color: #b00020; font-size: 12px; }}
</style></head><body>
<h1>Benchmark OCR — tableau de décision</h1>
<div class="infos">{infos}</div>
<h2>Résultats</h2>
<table><tr>{tete}</tr>{corps}</table>
<p class="infos">En vert : meilleur résultat de la colonne. F1 : mots corrects retrouvés (plus haut = mieux).
Nombres : nombres de 2 chiffres ou plus retrouvés. Temps : OCR médian, hors chargement du modèle.</p>
<h2>Conclusion</h2>
<div class="conclusion">{concl}</div>
<h2>Détails par document</h2>
{''.join(details)}
</body></html>"""


# ═════════════════════════════════════════════════════════════════════════
# Programme principal
# ═════════════════════════════════════════════════════════════════════════

def main():
    if len(sys.argv) >= 3 and sys.argv[1] == "--worker":
        worker(sys.argv[2])
        return

    p = argparse.ArgumentParser(description="Benchmark docTR / PaddleOCR FR / PaddleOCR AR.")
    p.add_argument("fichiers", nargs="*", help="documents à tester (PDF ou image)")
    p.add_argument("--doc", nargs=2, action="append", default=[], metavar=("FICHIER", "VERITE"),
                   help="document + sa vérité terrain (.txt UTF-8), si vous en avez une")
    p.add_argument("--runs", type=int, default=2)
    p.add_argument("--mkldnn", action="store_true")
    args = p.parse_args()
    runs = max(1, args.runs)

    entrees = [(f, None) for f in args.fichiers] + [tuple(d) for d in args.doc]
    if not entrees:
        p.error("indiquez au moins un document, ex. : python benchmark_ocr.py doc_fr.pdf cin.pdf")

    docs = []
    for i, (fichier, verite) in enumerate(entrees, 1):
        f = Path(fichier)
        if not f.exists():
            sys.exit(f"Introuvable : {f}")
        texte = None
        if verite:
            v = Path(verite)
            if not v.exists():
                sys.exit(f"Introuvable : {v}")
            texte = v.read_text(encoding="utf-8")
        docs.append({"fichier": f.name, "chemin": f, "verite": texte,
                     "nb_mots": len(mots(texte)) if texte else None,
                     "nb_nombres": len(re.findall(r"\d{2,}", normaliser_eval(texte))) if texte else None,
                     "nom": f"Doc {i} ({f.stem[:20]})"})

    resultats = {}
    with tempfile.TemporaryDirectory() as dossier:
        images = [preparer_images(d["chemin"], Path(dossier) / f"doc_{i}") for i, d in enumerate(docs)]
        for cle in MOTEURS:
            print(f"── {MOTEURS[cle]['nom']} ".ljust(60, "─"), flush=True)
            r = lancer_moteur(cle, images, dossier, runs, args.mkldnn)
            if r.get("ok"):
                for d, pd in zip(docs, r["par_doc"]):
                    pd["median"] = statistics.median(pd["temps"][1:] or pd["temps"])
                    pd["lecture"] = compter_lecture(pd["mots_scores"])
                    pd["mesures"] = (calculer_mesures("\n".join(pd["lignes"]), d["verite"])
                                     if d["verite"] else None)
                print(f"   OK — chargement {r['chargement']:.1f}s", flush=True)
            else:
                print(f"   ERREUR : {r['erreur'].splitlines()[0]}", flush=True)
            resultats[cle] = r

    entetes, lignes, gagnants = tableau_decision(docs, resultats)
    phrases = conclusion(docs, resultats)
    afficher_terminal(entetes, lignes, gagnants, phrases)

    infos = (f"Documents : {', '.join(d['nom'] + ' (' + d['fichier'] + ')' for d in docs)}<br>"
             f"{DPI} DPI · {runs} passage(s) par document · oneDNN PaddleOCR : "
             f"{'activé' if args.mkldnn else 'désactivé'}<br>"
             f"{platform.system()} {platform.release()} — {platform.processor() or platform.machine()} · "
             f"Python {platform.python_version()} · {datetime.now():%d/%m/%Y %H:%M}<br>"
             + ", ".join(f"{x} {version(x)}" for x in
                         ("python-doctr", "torch", "paddleocr", "paddlepaddle", "pymupdf")))

    rapport = Path("benchmark_ocr.html").resolve()
    rapport.write_text(generer_html(docs, resultats, entetes, lignes, gagnants, phrases, infos),
                       encoding="utf-8")
    with open("benchmark_ocr.csv", "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f, delimiter=";")
        w.writerow(entetes)
        w.writerows(lignes)
    Path("benchmark_ocr.json").write_text(json.dumps(
        {"documents": [{k: v for k, v in d.items() if k != "chemin"} for d in docs],
         "resultats": resultats, "conclusion": phrases}, ensure_ascii=False, indent=2),
        encoding="utf-8")

    print(f"Rapport : {rapport}")
    webbrowser.open(rapport.as_uri())


if __name__ == "__main__":
    main()