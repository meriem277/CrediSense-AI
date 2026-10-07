# services/controle_type.py
"""
Contrôle du type de document : le type DÉCLARÉ (l'emplacement choisi par le client à
l'upload) est comparé au type DÉTECTÉ par la classification (embeddings, puis LLM).

Pourquoi : sans ce contrôle, un relevé bancaire déposé dans l'emplacement « fiche de paie »
est traité comme une fiche de paie, et le revenu comme les dettes deviennent faux.

Règle de prudence : on ne signale un conflit que si la classification est FIABLE.
Un verdict incertain (texte OCR bruité, zone grise sans réponse du LLM) n'est jamais un
conflit : il n'empêche pas l'analyse.

La confiance des embeddings (similarité cosinus) et celle du LLM (probabilité déclarée)
ne sont pas comparables : chaque méthode a donc son propre seuil de fiabilité.
"""

from typing import Optional

TYPES_NON_DECLARES = (None, "", "AUTRE")

# Au-dessus de ce score, un verdict par embeddings est fiable (= SEUIL_CONFIANCE_ELEVEE du
# classifieur en cascade : en dessous, la cascade passe au LLM).
SEUIL_FIABLE_EMBEDDINGS = 0.55
# Le LLM annonce sa propre confiance (0 à 1) : on exige qu'elle soit nette.
SEUIL_FIABLE_LLM = 0.70
# Les règles répondent (confiance fixe de 0,90) ou s'abstiennent : un verdict de règles est fiable.
SEUIL_FIABLE_REGLES = 0.80


def seuil_fiabilite(methode: Optional[str]) -> Optional[float]:
    """Seuil de fiabilité selon la méthode ; None = verdict jamais fiable."""
    if methode == "regles":
        return SEUIL_FIABLE_REGLES
    if methode == "embeddings":
        return SEUIL_FIABLE_EMBEDDINGS
    if methode == "llm_fallback":
        return SEUIL_FIABLE_LLM
    # « embeddings_faible_confiance » et « embeddings_llm_indisponible » :
    # la classification n'a pas pu trancher
    return None


def evaluer_type(declare: Optional[str], classification: dict) -> dict:
    """
    Compare le type déclaré au résultat de classification.

    Retourne :
      typeDetecte : catégorie trouvée par la classification
      confiance   : sa confiance (échelle propre à la méthode)
      methode     : « embeddings », « llm_fallback »…
      fiable      : le verdict est assez sûr pour être utilisé
      concordant  : True / False, ou None si on ne peut pas conclure
                    (type non déclaré, verdict peu fiable ou « AUTRE »)
      typeRetenu  : type à utiliser pour la suite (extraction JSON) : le type détecté
                    s'il est fiable, sinon le type déclaré
    """
    detecte   = (classification.get("type_document") or "AUTRE").upper()
    methode   = classification.get("methode")
    try:
        confiance = float(classification.get("confiance") or 0.0)
    except (TypeError, ValueError):
        confiance = 0.0

    seuil  = seuil_fiabilite(methode)
    fiable = detecte != "AUTRE" and seuil is not None and confiance >= seuil

    declare_norm = (declare or "").upper() or None
    if declare_norm in TYPES_NON_DECLARES or not fiable:
        concordant = None
    else:
        concordant = declare_norm == detecte

    if fiable:
        retenu = detecte
    else:
        retenu = declare_norm or "AUTRE"

    return {
        "typeDetecte": detecte,
        "confiance":   round(confiance, 4),
        "methode":     methode,
        "fiable":      fiable,
        "concordant":  concordant,
        "typeRetenu":  retenu,
    }
