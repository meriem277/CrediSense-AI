# services/classification_regles.py
"""
Classification des documents PAR RÈGLES (mots-clés), premier niveau de la cascade :

    règles  →  embeddings (MiniLM)  →  LLM

Pourquoi : mesurés sur les pièces du dossier de test, les embeddings seuls se trompent
souvent (une attestation de travail qui cite « la carte d'identité nationale n° … » est
classée CIN, un relevé bancaire aussi) et leur score n'est qu'une similarité, pas une
probabilité. Les titres de documents, eux, sont très reconnaissables : « ATTESTATION DE
TRAVAIL », « BULLETIN DE PAIE », « RELEVÉ DE COMPTE », « CARTE D'IDENTITÉ NATIONALE »…

Principe : chaque type a des mots-clés de trois forces.
  - titres  : le nom du document ; comptent surtout quand ils sont tout en haut du texte
              (plein poids dans les 150 premiers caractères, poids réduit jusqu'à 400). Une
              attestation qui cite la carte d'identité n'est pas une carte d'identité ;
  - forts   : vocabulaire propre au type, partout dans le texte ;
  - faibles : indices secondaires.
Les règles ne répondent que si le verdict est net (score assez haut ET écart suffisant avec le
2e type) ; sinon elles s'abstiennent et la cascade continue avec les embeddings puis le LLM.
Aucune règle ne produit « AUTRE » : l'absence de verdict n'est pas un verdict.

Le texte est normalisé (accents et voyelles arabes retirés, apostrophes neutralisées) pour
rester robuste aux erreurs d'OCR ; français et arabe sont gérés.
"""

import re
import unicodedata
from typing import Optional

TAILLE_ENTETE   = 400    # caractères (texte normalisé) considérés comme « en-tête »
ZONE_TITRE      = 150    # un titre est « en haut du document » s'il commence dans ces premiers caractères
POIDS_TITRE_ENTETE = 5   # titre tout en haut
POIDS_TITRE_TARDIF = 3   # titre dans l'en-tête mais plus bas (souvent une simple mention)
POIDS_FORT         = 3
BONUS_FORT_ENTETE  = 2
POIDS_FAIBLE       = 1

SCORE_MIN = 5            # score minimal du type retenu
ECART_MIN = 3            # écart minimal avec le 2e type
CONFIANCE_REGLES = 0.90  # les règles répondent ou s'abstiennent : pas de demi-mesure

_APOSTROPHES = re.compile(r"[’'`´ʼ]")
_NON_MOT     = re.compile(r"[^\w\s]")
_ESPACES     = re.compile(r"\s+")
_ALEF        = str.maketrans({"أ": "ا", "إ": "ا", "آ": "ا", "ى": "ي", "ة": "ه"})


def normaliser(texte: str) -> str:
    """Minuscules, sans accents ni voyelles arabes, apostrophes et ponctuation → espaces."""
    t = unicodedata.normalize("NFKC", texte or "")
    t = "".join(c for c in unicodedata.normalize("NFKD", t) if not unicodedata.combining(c))
    t = t.lower().translate(_ALEF)
    t = _APOSTROPHES.sub(" ", t)
    t = _NON_MOT.sub(" ", t)
    return _ESPACES.sub(" ", t).strip()


def _n(liste: list[str]) -> list[str]:
    return [normaliser(m) for m in liste]


# titres_hors_entete : poids d'un titre trouvé HORS de l'en-tête (0 = ne compte pas du tout)
REGLES: dict[str, dict] = {
    "CIN": {
        "titres": _n(["carte d'identité nationale", "carte nationale d'identité", "بطاقة التعريف الوطنية"]),
        "titres_hors_entete": 0,    # une attestation qui cite la CIN dans son corps n'est pas une CIN
        "forts": [],
        "faibles": _n(["lieu de naissance", "date de naissance", "délivrée le", "مكان الولادة",
                       "تاريخ الولادة", "اللقب", "république tunisienne", "الجمهورية التونسية"]),
    },
    "FICHE_PAIE": {
        "titres": _n(["bulletin de paie", "fiche de paie", "bulletin de salaire", "fiche de salaire",
                      "قسيمة الأجر", "قسيمة الراتب", "ورقة الأجر"]),
        "titres_hors_entete": POIDS_FORT,
        "forts": _n(["net à payer", "salaire brut", "net imposable"]),
        "faibles": _n(["cotisations", "cnss", "retenues", "irpp", "matricule", "salaire de base",
                       "période de paie", "الأجر الصافي", "الأجر الخام"]),
    },
    "RELEVE_BANCAIRE": {
        "titres": _n(["relevé de compte", "relevé bancaire", "extrait de compte", "كشف حساب", "كشف الحساب"]),
        "titres_hors_entete": POIDS_FORT,
        "forts": _n(["ancien solde", "nouveau solde", "solde créditeur", "solde débiteur"]),
        "faibles": _n(["solde", "débit", "crédit", "virement", "prélèvement", "rib", "iban",
                       "date valeur", "opérations", "agence"]),
    },
    "ATTESTATION_EMPLOI": {
        "titres": _n(["attestation de travail", "attestation d'emploi", "attestation de salaire",
                      "attestation employeur", "شهادة عمل", "شهادة شغل"]),
        "titres_hors_entete": 0,
        "forts": [],
        "faibles": _n(["atteste", "attestons", "est employé", "occupe le poste", "en qualité de",
                       "date d'embauche", "toujours en poste", "نشهد", "تشهد", "يشتغل", "بصفة"]),
    },
    "CONTRAT_TRAVAIL": {
        "titres": _n(["contrat de travail", "عقد شغل", "عقد عمل"]),
        "titres_hors_entete": 0,
        "forts": _n(["entre les soussignés", "période d'essai", "l'employeur et le salarié", "préavis"]),
        "faibles": _n(["article", "rémunération", "les parties", "résiliation", "durée de travail"]),
    },
    "JUSTIFICATIF_DOMICILE": {
        "titres": _n(["facture", "quittance de loyer", "quittance", "justificatif de domicile",
                      "attestation de résidence", "certificat de résidence", "فاتورة", "شهادة إقامة"]),
        "titres_hors_entete": 0,    # « paiement facture » dans un relevé ne fait pas une facture
        "forts": _n(["steg", "sonede", "tunisie telecom", "ooredoo", "société tunisienne de l'électricité",
                     "الشركة التونسية للكهرباء والغاز", "الصوناد"]),
        "faibles": _n(["adresse", "kwh", "abonné", "référence client", "échéance", "loyer", "locataire", "كراء"]),
    },
    "ASSURANCE_VIE": {
        "titres": _n(["assurance vie", "assurance-vie", "contrat d'assurance", "التأمين على الحياة"]),
        "titres_hors_entete": POIDS_FORT,
        "forts": _n(["capital décès", "bénéficiaire", "souscripteur"]),
        "faibles": _n(["prime", "police n", "assuré", "garantie"]),
    },
    "BILAN_COMPTABLE": {
        "titres": _n(["bilan comptable", "bilan", "états financiers"]),
        "titres_hors_entete": 0,
        "forts": _n(["capitaux propres", "total actif", "total passif"]),
        "faibles": _n(["actif", "passif", "chiffre d'affaires", "exercice clos"]),
    },
    "DECLARATION_FISCALE": {
        "titres": _n(["déclaration d'impôt", "déclaration de revenu", "déclaration fiscale",
                      "déclaration de l'impôt sur le revenu", "الإقرار"]),
        "titres_hors_entete": 0,
        "forts": _n(["impôt sur le revenu", "matricule fiscal", "revenu imposable"]),
        "faibles": _n(["recette des finances", "année d'imposition", "irpp"]),
    },
    "TITRE_SEJOUR": {
        "titres": _n(["titre de séjour", "carte de séjour", "بطاقة إقامة"]),
        "titres_hors_entete": POIDS_FORT,
        "forts": _n(["autorisé à séjourner"]),
        "faibles": _n(["nationalité", "validité"]),
    },
}


def _contient(texte: str, mot: str) -> bool:
    """Le mot (ou l'expression) est présent en tant que mots entiers."""
    return re.search(rf"(?<!\w){re.escape(mot)}(?!\w)", texte) is not None


def scorer(texte_normalise: str) -> dict[str, dict]:
    """Score et mots-clés trouvés pour chaque type (sert aussi à l'audit)."""
    entete = texte_normalise[:TAILLE_ENTETE]
    resultats = {}
    for type_document, regle in REGLES.items():
        score, trouves = 0, []

        for mot in regle["titres"]:
            if _contient(entete, mot):
                position = entete.find(mot)
                score += POIDS_TITRE_ENTETE if position <= ZONE_TITRE else POIDS_TITRE_TARDIF
                trouves.append(mot)
            elif regle["titres_hors_entete"] and _contient(texte_normalise, mot):
                score += regle["titres_hors_entete"]
                trouves.append(mot)

        for mot in regle["forts"]:
            if _contient(texte_normalise, mot):
                score += POIDS_FORT + (BONUS_FORT_ENTETE if _contient(entete, mot) else 0)
                trouves.append(mot)

        for mot in regle["faibles"]:
            if _contient(texte_normalise, mot):
                score += POIDS_FAIBLE
                trouves.append(mot)

        resultats[type_document] = {"score": score, "mots_cles": trouves}
    return resultats


def diagnostiquer_regles(texte: str) -> dict:
    """
    Analyse complète des règles, décisive ou non, pour expliquer ce qui s'est passé :
      decisif    : les règles tranchent (score assez haut ET écart suffisant avec le 2e type)
      type, score, mots_cles : le meilleur candidat et les mots-clés qui l'ont fait gagner
      classement : les 3 meilleurs candidats avec leur score (pour comprendre une abstention)
      raison     : phrase lisible (« verdict net », « écart insuffisant… »)
    """
    seuils = {"seuil_score": SCORE_MIN, "seuil_ecart": ECART_MIN}
    if not texte or len(texte.strip()) < 20:
        return {"decisif": False, "type": None, "score": 0, "mots_cles": [], "classement": [],
                "raison": "texte trop court pour appliquer les règles", **seuils}

    scores = scorer(normaliser(texte))
    classement = sorted(scores.items(), key=lambda kv: kv[1]["score"], reverse=True)
    (meilleur, infos), (_, second) = classement[0], classement[1]
    ecart = infos["score"] - second["score"]

    if infos["score"] == 0:
        decisif, raison = False, "aucun mot-clé de type de document reconnu"
    elif infos["score"] < SCORE_MIN:
        decisif, raison = False, f"score trop bas ({infos['score']} : {SCORE_MIN} requis)"
    elif ecart < ECART_MIN:
        decisif, raison = False, f"écart insuffisant avec le 2e type ({ecart} : {ECART_MIN} requis)"
    else:
        decisif, raison = True, f"verdict net (score {infos['score']}, écart {ecart} avec le 2e type)"

    return {
        "decisif":    decisif,
        "type":       meilleur if infos["score"] else None,
        "score":      infos["score"],
        "mots_cles":  infos["mots_cles"],
        "classement": [{"type": t, "score": i["score"], "mots_cles": i["mots_cles"]}
                       for t, i in classement[:3] if i["score"] > 0],
        "raison":     raison,
        **seuils,
    }


def verdict_depuis_diagnostic(diagnostic: dict) -> Optional[dict]:
    """Le verdict (même forme que les autres classifieurs) si les règles sont décisives, sinon None."""
    if not diagnostic["decisif"]:
        return None
    return {
        "type_document": diagnostic["type"],
        "confiance":     CONFIANCE_REGLES,
        "methode":       "regles",
        "score_regles":  diagnostic["score"],
        "mots_cles":     diagnostic["mots_cles"],
        "alertes":       [],
    }


def classer_par_regles(texte: str) -> Optional[dict]:
    """
    Renvoie un verdict si les règles sont sûres d'elles, sinon None (la cascade continue).
    Le verdict a la même forme que celui des autres classifieurs, avec methode = « regles ».
    """
    return verdict_depuis_diagnostic(diagnostiquer_regles(texte))
