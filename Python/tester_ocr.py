"""
tester_ocr.py — Teste le service OCR seul, sans démarrer FastAPI.

À placer dans le dossier Python/ (à côté de main.py), puis :
    python tester_ocr.py chemin/vers/document.pdf
    python tester_ocr.py doc1.pdf doc2.pdf        (plusieurs fichiers)

Affiche dans le terminal : chargement des modèles, résumé du résultat, texte extrait.
Enregistre aussi le texte dans <nom_du_fichier>_ocr.txt (plus lisible pour l'arabe).
"""

import importlib.util
import logging
import sys
import time
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s",
                    datefmt="%H:%M:%S")

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass


def charger_classe_service():
    """
    Charge services/ocr_service.py directement, sans passer par services/__init__.py
    (qui pourrait importer les autres services : Groq, LLM, etc.).
    """
    chemin = Path(__file__).resolve().parent / "services" / "ocr_service.py"
    if not chemin.exists():
        sys.exit(f"Introuvable : {chemin} — placez ce script dans le dossier Python/")
    spec = importlib.util.spec_from_file_location("ocr_service", chemin)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.OcrService


def main():
    if len(sys.argv) < 2:
        sys.exit("Usage : python tester_ocr.py chemin/vers/document.pdf")

    print("\n══ Démarrage du service OCR ══", flush=True)
    t0 = time.time()
    service = charger_classe_service()()
    print(f"Service prêt en {time.time() - t0:.1f}s")
    for cle, valeur in service.stats().items():
        print(f"   {cle:12} : {valeur}")

    for fichier in sys.argv[1:]:
        print(f"\n══ OCR : {fichier} ══", flush=True)
        r = service.extraire(fichier, "pdf")

        if r["statut"] != "SUCCESS":
            print(f"ÉCHEC : {r.get('erreur')}")
            continue

        print(f"   statut          : {r['statut']}")
        print(f"   cas             : {r['cas']}")
        print(f"   moteur          : {r['moteur']}")
        print(f"   langue détectée : {r['langue_detectee']}")
        print(f"   pages           : {r['nb_pages']}")
        print(f"   confiance       : {r['confidence']}")
        print(f"   zones écartées  : {r['zones_ecartees']}")
        print(f"   durée           : {r['duree_ms'] / 1000:.1f}s")
        print(f"   caractères      : {len(r['texte'])}")

        print("\n── Texte extrait " + "─" * 44)
        print(r["texte"])
        print("─" * 60)

        sortie = Path(fichier).with_name(Path(fichier).stem + "_ocr.txt")
        sortie.write_text(r["texte"], encoding="utf-8")
        print(f"Texte enregistré dans : {sortie}")

        # 2e appel : doit être instantané grâce au cache
        t0 = time.time()
        service.extraire(fichier, "pdf")
        print(f"2e appel (cache) : {(time.time() - t0) * 1000:.0f} ms")


if __name__ == "__main__":
    main()