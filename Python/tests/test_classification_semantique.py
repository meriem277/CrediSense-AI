# tests/test_classification_semantique.py
"""
Évaluation de la classification par similarité sémantique (section 4.5.2.1)

Ce script teste le VRAI classifieur de CrediSense (services/nlp_service.py,
modèle paraphrase-multilingual-MiniLM-L12-v2) sur un jeu de textes dont la
catégorie est connue à l'avance, puis calcule :

  - l'exactitude globale ;
  - le rappel et la précision par catégorie ;
  - la matrice de confusion ;
  - la confiance moyenne (bonnes vs mauvaises prédictions) ;
  - la répartition dans les zones de la cascade (≥ 0,55 / 0,20–0,55 / < 0,20)
    et l'exactitude des décisions prises directement (≥ 0,55) ;
  - le temps moyen de classification par document.

Le LLM n'est PAS appelé : on mesure uniquement la classification locale.

Sources de test :
  1. Jeu intégré (ci-dessous) : textes FICTIFS mais réalistes, en français
     et en arabe, dont certains avec des erreurs d'OCR simulées.
  2. (optionnel) Dossier tests/textes/ : fichiers .txt contenant de VRAIS
     textes OCR, nommés  <CATEGORIE>__<nom>.txt
     ex : FICHE_PAIE__client1.txt, CIN__scan2.txt

Utilisation (depuis le dossier Python\\) :
    python tests/test_classification_semantique.py
    python tests/test_classification_semantique.py --seulement-dossier

Résultats : affichés dans le terminal + enregistrés dans tests/resultats/
"""

import argparse
import csv
import statistics
import sys
import time
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

# ── Accès au code du projet (dossier Python\) ─────────────────────────────────
RACINE = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(RACINE))

# Affichage UTF-8 dans le terminal Windows
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

SEUIL_ELEVE = 0.55
SEUIL_BAS   = 0.20

CATEGORIES = [
    "CIN", "FICHE_PAIE", "RELEVE_BANCAIRE", "ATTESTATION_EMPLOI",
    "CONTRAT_TRAVAIL", "ASSURANCE_VIE", "BILAN_COMPTABLE",
    "DECLARATION_FISCALE", "JUSTIFICATIF_DOMICILE", "TITRE_SEJOUR", "AUTRE",
]

# ── Jeu de test intégré (données fictives) ────────────────────────────────────
# (identifiant, catégorie attendue, langue, texte)
JEU_INTEGRE = [
    # CIN
    ("cin_fr", "CIN", "FR",
     "REPUBLIQUE TUNISIENNE\nCARTE D'IDENTITE NATIONALE\nNom : BEN SALAH\nPrénom : Amine\n"
     "Né le : 14/03/1995 à Sfax\nN° 09876543\nDate de délivrance : 02/05/2019"),
    ("cin_ar", "CIN", "AR",
     "الجمهورية التونسية\nبطاقة التعريف الوطنية\nاللقب : بن صالح\nالاسم : أمين\n"
     "تاريخ الولادة : 14 مارس 1995\nمكانها : صفاقس\nالرقم 09876543"),
    ("cin_ocr_bruite", "CIN", "FR",
     "REPUBL1QUE TUN1SIENNE\nCARTE D 1DENTITE NAT10NALE\nN0m BEN SALAH Prenom Am1ne\n"
     "Ne le 14/O3/1995 Sfax N° O9876543"),

    # FICHE DE PAIE
    ("paie_fr", "FICHE_PAIE", "FR",
     "BULLETIN DE PAIE\nPériode : Septembre 2026\nEmployeur : SOCIETE ALPHA SARL\n"
     "Matricule : 1042\nSalaire de base : 2 800,000\nPrime de rendement : 200,000\n"
     "Salaire brut : 3 000,000\nCotisations CNSS : 275,000\nRetenue IRPP : 310,000\n"
     "Net à payer : 2 415,000 DT"),
    ("paie_ar", "FICHE_PAIE", "AR",
     "كشف الأجر\nالشهر : سبتمبر 2026\nالمؤجر : شركة ألفا\nالأجر الأساسي : 2800 دينار\n"
     "منحة المردودية : 200 دينار\nالأجر الخام : 3000 دينار\nالاقتطاعات : 585 دينار\n"
     "الصافي للدفع : 2415 دينار"),
    ("paie_ocr_bruite", "FICHE_PAIE", "FR",
     "BULLET1N DE PA1E Sept 2026\nSalaire de ba5e 2 8OO,OOO\nC0tisations CN5S 275,OOO\n"
     "NET A PAYER 2 415,OOO"),

    # RELEVÉ BANCAIRE
    ("releve_fr", "RELEVE_BANCAIRE", "FR",
     "RELEVE DE COMPTE\nTitulaire : M. Amine BEN SALAH\nPériode du 01/09/2026 au 30/09/2026\n"
     "Ancien solde créditeur : 3 120,500\n05/09 VIREMENT SALAIRE SOCIETE ALPHA 2 415,000\n"
     "08/09 RETRAIT DAB 200,000\n15/09 PRELEVEMENT STEG 85,300\nNouveau solde : 5 250,200"),
    ("releve_ar", "RELEVE_BANCAIRE", "AR",
     "كشف حساب بنكي\nصاحب الحساب : أمين بن صالح\nالرصيد السابق : 3120 دينار\n"
     "تحويل الأجر : 2415 دينار\nسحب نقدي : 200 دينار\nالرصيد الجديد : 5250 دينار"),

    # ATTESTATION D'EMPLOI
    ("attestation_fr", "ATTESTATION_EMPLOI", "FR",
     "ATTESTATION DE TRAVAIL\nNous soussignés, SOCIETE ALPHA SARL, attestons que M. Amine BEN SALAH "
     "est employé au sein de notre société depuis le 01/02/2021 en qualité d'ingénieur, "
     "dans le cadre d'un contrat à durée indéterminée.\n"
     "La présente attestation est délivrée à l'intéressé pour servir et valoir ce que de droit."),
    ("attestation_ar", "ATTESTATION_EMPLOI", "AR",
     "شهادة عمل\nنشهد نحن شركة ألفا أن السيد أمين بن صالح يعمل لدينا منذ 01/02/2021 "
     "بصفة مهندس بعقد غير محدد المدة.\nسلمت هذه الشهادة للمعني بالأمر للإدلاء بها عند الحاجة."),

    # CONTRAT DE TRAVAIL
    ("contrat_fr", "CONTRAT_TRAVAIL", "FR",
     "CONTRAT DE TRAVAIL A DUREE INDETERMINEE\nEntre les soussignés : SOCIETE ALPHA SARL, "
     "ci-après dénommée l'employeur, et M. Amine BEN SALAH, ci-après dénommé le salarié.\n"
     "Article 1 : Engagement. Article 2 : Période d'essai de six mois. "
     "Article 3 : Rémunération mensuelle brute. Article 4 : Durée du travail."),

    # ASSURANCE VIE
    ("assurance_fr", "ASSURANCE_VIE", "FR",
     "CONTRAT D'ASSURANCE VIE\nSouscripteur : M. Amine BEN SALAH\nAssureur : COMPAGNIE ASSURANCES BETA\n"
     "Capital garanti : 50 000,000 DT\nBénéficiaires en cas de décès : conjoint et enfants\n"
     "Prime annuelle : 600,000 DT\nDurée du contrat : 15 ans"),

    # BILAN COMPTABLE
    ("bilan_fr", "BILAN_COMPTABLE", "FR",
     "BILAN AU 31 DECEMBRE 2025\nACTIFS NON COURANTS : Immobilisations corporelles 120 000\n"
     "ACTIFS COURANTS : Stocks 45 000, Clients 30 000, Liquidités 12 000\n"
     "CAPITAUX PROPRES : Capital social 100 000, Résultat de l'exercice 18 500\n"
     "PASSIFS : Fournisseurs 25 000, Emprunts 63 500\nTOTAL DU BILAN 207 000"),

    # DÉCLARATION FISCALE
    ("fiscale_fr", "DECLARATION_FISCALE", "FR",
     "DECLARATION ANNUELLE DE L'IMPOT SUR LE REVENU DES PERSONNES PHYSIQUES\nAnnée 2025\n"
     "Identifiant fiscal : 1234567/A/M/000\nRevenus salariaux : 36 000\n"
     "Revenu net imposable : 31 200\nImpôt dû : 6 100\nRetenues à la source : 6 100"),

    # JUSTIFICATIF DE DOMICILE
    ("domicile_steg", "JUSTIFICATIF_DOMICILE", "FR",
     "STEG — SOCIETE TUNISIENNE DE L'ELECTRICITE ET DU GAZ\nFACTURE\nAbonné : M. Amine BEN SALAH\n"
     "Adresse : 12 rue des Oliviers, Sfax\nRéférence client : 456789\n"
     "Consommation électricité : 320 kWh\nMontant à payer : 85,300 DT"),
    ("domicile_sonede", "JUSTIFICATIF_DOMICILE", "FR",
     "SONEDE — FACTURE D'EAU\nAbonné : M. Amine BEN SALAH\nAdresse de consommation : "
     "12 rue des Oliviers, Sfax\nIndex ancien 1250 — Index nouveau 1290\nMontant TTC : 32,750 DT"),

    # TITRE DE SÉJOUR
    ("sejour_fr", "TITRE_SEJOUR", "FR",
     "CARTE DE SEJOUR\nREPUBLIQUE TUNISIENNE — MINISTERE DE L'INTERIEUR\nNom : DUPONT\n"
     "Prénom : Marie\nNationalité : française\nType : résident temporaire\n"
     "Valable jusqu'au : 31/12/2027"),

    # AUTRE
    ("autre_recette", "AUTRE", "FR",
     "Recette du couscous au poisson : faire revenir les oignons, ajouter la tomate, "
     "le piment et les épices, puis cuire la semoule à la vapeur pendant 30 minutes."),
    ("autre_menu", "AUTRE", "FR",
     "Menu du restaurant — Entrées : salade méchouia, brick à l'œuf. Plats : ojja merguez, "
     "poisson grillé. Desserts : bambalouni, assida."),
]


# ── Chargement des vrais textes OCR (optionnel) ───────────────────────────────
def charger_dossier(dossier: Path) -> list:
    echantillons = []
    if not dossier.exists():
        return echantillons
    for fichier in sorted(dossier.glob("*.txt")):
        categorie = fichier.stem.split("__")[0].upper()
        if categorie not in CATEGORIES:
            print(f"  ⚠ Ignoré (préfixe inconnu) : {fichier.name}")
            continue
        texte = fichier.read_text(encoding="utf-8", errors="ignore")
        echantillons.append((fichier.stem, categorie, "réel", texte))
    return echantillons


# ── Lecture robuste du résultat du classifieur ────────────────────────────────
def lire_resultat(resultat: dict) -> tuple:
    categorie = None
    for cle in ("type_document", "categorie", "category", "type", "label"):
        if resultat.get(cle):
            categorie = str(resultat[cle]).upper()
            break
    confiance = float(resultat.get("confiance", resultat.get("confidence", 0.0)) or 0.0)
    return categorie or "AUTRE", confiance


def zone(confiance: float) -> str:
    if confiance >= SEUIL_ELEVE:
        return "directe"
    if confiance < SEUIL_BAS:
        return "faible"
    return "grise"


def fmt(x: float, d: int = 2) -> str:
    return f"{x:.{d}f}".replace(".", ",")


# ── Programme principal ───────────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--seulement-dossier", action="store_true",
                        help="n'utiliser que les vrais textes de tests/textes/")
    args = parser.parse_args()

    print("=" * 96)
    print(" CrediSense — Évaluation de la classification par similarité sémantique")
    print(f" {datetime.now():%d/%m/%Y %H:%M}")
    print("=" * 96)

    # 1. Chargement du modèle (mesuré à part)
    print("\nChargement du modèle d'embeddings…")
    t0 = time.perf_counter()
    from services.nlp_service import NLPClassifier
    classifieur = NLPClassifier()
    t_chargement = (time.perf_counter() - t0) * 1000
    print(f"Modèle chargé en {t_chargement:,.0f} ms".replace(",", " "))

    # 2. Jeu de test
    echantillons = [] if args.seulement_dossier else list(JEU_INTEGRE)
    reels = charger_dossier(Path(__file__).resolve().parent / "textes")
    echantillons += reels
    if not echantillons:
        print("Aucun échantillon à tester.")
        return
    print(f"Échantillons : {len(echantillons)} "
          f"({len(echantillons) - len(reels)} intégrés, {len(reels)} réels)\n")

    # Préchauffage (le premier encodage est toujours plus lent)
    classifieur.classify("préchauffage du modèle", None)

    # 3. Classification
    lignes = []
    entete = f"{'Document':<22}{'Langue':<8}{'Attendu':<23}{'Prédit':<23}{'Conf.':>6}  {'Zone':<8}{'ms':>6}  OK"
    print(entete)
    print("-" * len(entete))
    for ident, attendu, langue, texte in echantillons:
        t = time.perf_counter()
        resultat = classifieur.classify(texte, None)
        duree = (time.perf_counter() - t) * 1000

        predit, confiance = lire_resultat(resultat)
        z = zone(confiance)
        # Règle de la cascade : sous 0,20 le document est considéré non identifiable
        predit_effectif = "AUTRE" if z == "faible" else predit
        correct = predit_effectif == attendu

        lignes.append({
            "document": ident, "langue": langue, "attendu": attendu,
            "predit": predit, "predit_effectif": predit_effectif,
            "confiance": round(confiance, 4), "zone": z,
            "duree_ms": round(duree, 1), "correct": correct,
        })
        print(f"{ident[:21]:<22}{langue:<8}{attendu:<23}{predit_effectif:<23}"
              f"{fmt(confiance):>6}  {z:<8}{duree:>6.0f}  {'✓' if correct else '✗'}")

    # 4. Indicateurs
    n        = len(lignes)
    corrects = sum(l["correct"] for l in lignes)
    conf_ok  = [l["confiance"] for l in lignes if l["correct"]]
    conf_ko  = [l["confiance"] for l in lignes if not l["correct"]]
    durees   = [l["duree_ms"] for l in lignes]
    zones    = Counter(l["zone"] for l in lignes)
    directs  = [l for l in lignes if l["zone"] == "directe"]
    directs_ok = sum(l["correct"] for l in directs)

    print("\n" + "=" * 96)
    print(" INDICATEURS")
    print("=" * 96)
    print(f" Documents testés                         : {n}")
    print(f" Exactitude globale                       : {corrects}/{n}  ({fmt(corrects / n * 100, 1)} %)")
    for langue in sorted({l['langue'] for l in lignes}):
        sous = [l for l in lignes if l["langue"] == langue]
        ok = sum(l["correct"] for l in sous)
        print(f"   dont {langue:<6}                            : {ok}/{len(sous)}  ({fmt(ok / len(sous) * 100, 1)} %)")
    print(f" Confiance moyenne (bonnes prédictions)   : {fmt(statistics.mean(conf_ok)) if conf_ok else '–'}")
    print(f" Confiance moyenne (erreurs)              : {fmt(statistics.mean(conf_ko)) if conf_ko else '–'}")
    print(f" Temps moyen de classification            : {fmt(statistics.mean(durees), 1)} ms / document")
    print(f" Temps de chargement du modèle            : {t_chargement:,.0f} ms (une seule fois au démarrage)".replace(",", " "))

    print("\n Répartition dans la cascade :")
    print(f"   score ≥ {fmt(SEUIL_ELEVE)}  → décision directe       : {zones['directe']:>3}  ({fmt(zones['directe'] / n * 100, 1)} %)")
    print(f"   {fmt(SEUIL_BAS)} – {fmt(SEUIL_ELEVE)} → zone grise (appel LLM) : {zones['grise']:>3}  ({fmt(zones['grise'] / n * 100, 1)} %)")
    print(f"   score < {fmt(SEUIL_BAS)}  → non identifiable       : {zones['faible']:>3}  ({fmt(zones['faible'] / n * 100, 1)} %)")
    if directs:
        print(f"   Exactitude des décisions directes      : {directs_ok}/{len(directs)}  "
              f"({fmt(directs_ok / len(directs) * 100, 1)} %)")

    # Par catégorie
    print("\n Résultats par catégorie :")
    print(f"   {'Catégorie':<24}{'N':>3}{'Corrects':>10}{'Rappel':>9}{'Précision':>11}")
    vrais_pos = Counter(l["attendu"] for l in lignes if l["correct"])
    predits   = Counter(l["predit_effectif"] for l in lignes)
    attendus  = Counter(l["attendu"] for l in lignes)
    par_categorie = []
    for c in CATEGORIES:
        if attendus[c] == 0 and predits[c] == 0:
            continue
        rappel    = vrais_pos[c] / attendus[c] if attendus[c] else None
        precision = vrais_pos[c] / predits[c] if predits[c] else None
        par_categorie.append((c, attendus[c], vrais_pos[c], rappel, precision))
        print(f"   {c:<24}{attendus[c]:>3}{vrais_pos[c]:>10}"
              f"{(fmt(rappel * 100, 0) + ' %') if rappel is not None else '–':>9}"
              f"{(fmt(precision * 100, 0) + ' %') if precision is not None else '–':>11}")

    # Erreurs
    erreurs = [l for l in lignes if not l["correct"]]
    if erreurs:
        print("\n Erreurs de classification :")
        for l in erreurs:
            print(f"   {l['document']:<22} attendu {l['attendu']:<22} → prédit {l['predit_effectif']} "
                  f"(confiance {fmt(l['confiance'])}, zone {l['zone']})")

    # Matrice de confusion
    presentes = [c for c in CATEGORIES if attendus[c] or predits[c]]
    abrev = {c: c[:6] for c in presentes}
    matrice = defaultdict(int)
    for l in lignes:
        matrice[(l["attendu"], l["predit_effectif"])] += 1
    print("\n Matrice de confusion (lignes = attendu, colonnes = prédit) :")
    print("   " + " " * 24 + "".join(f"{abrev[c]:>8}" for c in presentes))
    for a in presentes:
        print(f"   {a:<24}" + "".join(f"{(matrice[(a, p)] or '.'):>8}" for p in presentes))

    # 5. Export
    dossier_res = Path(__file__).resolve().parent / "resultats"
    dossier_res.mkdir(exist_ok=True)
    horodatage = datetime.now().strftime("%Y%m%d_%H%M")
    chemin_csv = dossier_res / f"classification_{horodatage}.csv"
    with open(chemin_csv, "w", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=list(lignes[0].keys()), delimiter=";")
        writer.writeheader()
        writer.writerows(lignes)
    print(f"\n Détail enregistré : {chemin_csv}")

    try:
        import matplotlib
        matplotlib.use("Agg")
        import matplotlib.pyplot as plt

        donnees = [[matrice[(a, p)] for p in presentes] for a in presentes]
        fig, ax = plt.subplots(figsize=(8, 7))
        ax.imshow(donnees, cmap="Oranges")
        ax.set_xticks(range(len(presentes)), labels=presentes, rotation=45, ha="right", fontsize=8)
        ax.set_yticks(range(len(presentes)), labels=presentes, fontsize=8)
        for i, ligne in enumerate(donnees):
            for j, v in enumerate(ligne):
                if v:
                    ax.text(j, i, v, ha="center", va="center", fontsize=9)
        ax.set_xlabel("Catégorie prédite")
        ax.set_ylabel("Catégorie attendue")
        ax.set_title("Matrice de confusion — classification sémantique")
        fig.tight_layout()
        chemin_png = dossier_res / f"matrice_confusion_{horodatage}.png"
        fig.savefig(chemin_png, dpi=150)
        print(f" Matrice de confusion (image) : {chemin_png}")
    except ImportError:
        print(" (matplotlib absent : pas d'image de matrice de confusion)")

    print("=" * 96)


if __name__ == "__main__":
    main()