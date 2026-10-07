# services/decoupage_rag.py
"""
Découpage (« chunking ») des documents pour le RAG du chatbot.

Avant : fenêtre glissante par POSITION (2 lignes avant, 3 lignes après), sans regarder le
contenu. Sur un relevé bancaire, une opération était coupée en deux et les montants
perdaient l'en-tête « Débit / Crédit / Solde » qui leur donne leur sens.

Maintenant, le découpage suit la STRUCTURE et le SENS du document :

  1. Un relevé bancaire est découpé par OPÉRATION (une ligne datée = un chunk). Chaque
     chunk répète l'en-tête des colonnes, et le sens crédit / débit est DÉDUIT de
     l'évolution du solde (ancien solde + montant = nouveau solde → crédit).
  2. Un document court (CIN, attestation…) n'est jamais coupé : un seul chunk.
  3. Un texte libre est coupé là où le SUJET CHANGE : on compare les embeddings des
     lignes avant et après chaque frontière et on coupe là où la similarité chute
     (« semantic chunking »). Sans modèle d'embedding disponible, on coupe sur la taille.
  4. Aucun chunk ne dépasse MAX_CHARS_CHUNK : c'est environ ce que le modèle d'embedding
     lit réellement (128 tokens). Au-delà, la fin du chunk n'influencerait pas la recherche.

Chaque fonction est pure (pas de modèle chargé ici) : le modèle d'embedding est passé en
paramètre, ce qui permet de tester le découpage sans le télécharger.
"""

import re
import unicodedata
from typing import Callable, Optional

import numpy as np

# Taille maximale d'un chunk, en caractères. Le modèle MiniLM multilingue lit 128 tokens,
# soit environ 450 caractères de français ou d'arabe, en-tête de contexte compris.
MAX_CHARS_CHUNK = 450
# En dessous, une coupure sémantique est ignorée (un chunk trop court ne veut rien dire)
MIN_CHARS_CHUNK = 120
# On coupe aux frontières dont la similarité est parmi les 20 % les plus basses
PERCENTILE_COUPURE = 20

# Première ligne ajoutée par le backend devant chaque document : sert d'étiquette, pas de contenu
_RE_ENTETE_BACKEND = re.compile(r"^Document type \S+ nom .* :$")

# Une opération commence par une date : « 03/07/2026 » ou « 03/08 » (sans année)
_RE_DEBUT_OPERATION = re.compile(r"^\d{2}[/.\-]\d{2}(?:[/.\-]\d{2,4})?(?=\s|$)")
# Montant tunisien : « 250,000 », « 4 135,550 » (millimes obligatoires)
_RE_MONTANT = re.compile(r"(?<![\d,.])(?:\d{1,3}(?:[   ]\d{3})*|\d+),\d{3}(?!\d)")

_RE_TITRE_RELEVE = re.compile(r"releve de compte|extrait de compte|releve bancaire|كشف حساب")
_RE_FIN_TABLEAU  = re.compile(r"^(total|totaux|nouveau solde|solde final|solde de cloture)")
_COLONNES = {"date": "Date", "libelle": "Libellé", "debit": "Débit", "credit": "Crédit",
             "solde": "Solde", "valeur": "Valeur", "date valeur": "Date valeur"}

TOLERANCE_SOLDE = 0.002   # dinars : écart d'arrondi accepté quand on vérifie le solde


def _normaliser(texte: str) -> str:
    """Minuscules sans accents : « Libellé » → « libelle »."""
    decompose = unicodedata.normalize("NFKD", texte)
    return "".join(c for c in decompose if not unicodedata.combining(c)).lower().strip()


def _montant(valeur: str) -> float:
    return float(re.sub(r"[   ]", "", valeur).replace(",", "."))


# ══════════════════════════════════════════════════════════════════════════════
# Relevé bancaire : un chunk par opération
# ══════════════════════════════════════════════════════════════════════════════

def est_releve(lignes: list[str]) -> bool:
    """Un titre de relevé ET au moins deux lignes qui commencent par une date."""
    texte = _normaliser(" ".join(lignes[:15]))
    nb_dates = sum(1 for l in lignes if _RE_DEBUT_OPERATION.match(l))
    return bool(_RE_TITRE_RELEVE.search(texte)) and nb_dates >= 2


def _sens_operation(operation: str, solde_precedent: Optional[float]) -> tuple[Optional[str], Optional[float]]:
    """
    Déduit le sens (« crédit » / « débit ») d'une opération à partir de l'évolution du solde.
    Renvoie (sens ou None, nouveau solde ou None). Le texte d'un relevé ne dit pas dans quelle
    colonne se trouve un montant ; le solde, lui, ne ment pas : ancien + montant = nouveau → crédit.
    """
    montants = [_montant(m) for m in _RE_MONTANT.findall(operation)]
    if _normaliser(operation).find("ancien solde") >= 0:
        return None, (montants[-1] if montants else None)
    if len(montants) < 2:
        return None, solde_precedent
    montant, solde = montants[0], montants[-1]
    sens = None
    if solde_precedent is not None:
        if abs(solde_precedent + montant - solde) <= TOLERANCE_SOLDE:
            sens = "crédit"
        elif abs(solde_precedent - montant - solde) <= TOLERANCE_SOLDE:
            sens = "débit"
    return sens, solde


def _morceaux_releve(lignes: list[str]) -> list[tuple[str, str]]:
    """Renvoie [(texte du chunk, type)] : en-tête du relevé, une opération par chunk, pied de page."""
    preambule: list[str] = []
    operations: list[list[str]] = []
    pied: list[str] = []
    courante: Optional[list[str]] = None

    for ligne in lignes:
        if _RE_FIN_TABLEAU.match(_normaliser(ligne)):
            courante = None
            pied.append(ligne)
        elif pied:
            pied.append(ligne)
        elif _RE_DEBUT_OPERATION.match(ligne):
            courante = [ligne]
            operations.append(courante)
        elif courante is not None:
            courante.append(ligne)
        else:
            preambule.append(ligne)

    # En-tête des colonnes : les cellules « Date / Libellé / Débit / Crédit / Solde » de l'en-tête
    colonnes = [_COLONNES[_normaliser(l)] for l in preambule if _normaliser(l) in _COLONNES]
    entete = ("Colonnes du relevé : " + " | ".join(colonnes)) if len(colonnes) >= 3 else ""
    preambule = [l for l in preambule if _normaliser(l) not in _COLONNES]

    morceaux: list[tuple[str, str]] = []
    for texte in _chunks_par_taille(preambule):
        morceaux.append((texte, "bancaire"))

    solde = None
    for op in operations:
        texte_op = " ".join(op)
        sens, solde = _sens_operation(texte_op, solde)
        if sens:
            texte_op += f" (sens déduit du solde : {sens})"
        morceaux.append(("\n".join(p for p in (entete, texte_op) if p), "bancaire"))

    for texte in _chunks_par_taille(pied):
        morceaux.append((texte, "bancaire"))
    return morceaux


# ══════════════════════════════════════════════════════════════════════════════
# Texte libre : coupure aux changements de sujet
# ══════════════════════════════════════════════════════════════════════════════

def _couper_ligne_longue(ligne: str) -> list[str]:
    """Une ligne plus longue que MAX_CHARS_CHUNK est coupée aux phrases, puis aux mots."""
    if len(ligne) <= MAX_CHARS_CHUNK:
        return [ligne]
    morceaux, courant = [], ""
    for phrase in re.split(r"(?<=[.!?؟])\s+", ligne):
        for mot in phrase.split():
            if courant and len(courant) + 1 + len(mot) > MAX_CHARS_CHUNK:
                morceaux.append(courant)
                courant = mot
            else:
                courant = f"{courant} {mot}".strip()
    if courant:
        morceaux.append(courant)
    return morceaux


def _chunks_par_taille(lignes: list[str]) -> list[str]:
    """Regroupe des lignes consécutives sans dépasser MAX_CHARS_CHUNK (pas de modèle)."""
    return _regrouper(lignes, coupures=set())


def _regrouper(lignes: list[str], coupures: set[int]) -> list[str]:
    """
    Regroupe les lignes en chunks : on coupe sur une coupure sémantique (si le chunk courant a
    déjà MIN_CHARS_CHUNK) ou quand la taille maximale serait dépassée. Une coupure forcée par la
    taille garde la dernière ligne en chevauchement, pour ne pas perdre le fil ; une coupure
    sémantique n'en a pas besoin (le sujet change).
    """
    plates: list[str] = []
    for ligne in lignes:
        plates.extend(_couper_ligne_longue(ligne))

    chunks: list[list[str]] = []
    courant: list[str] = []
    taille = 0

    for i, ligne in enumerate(plates):
        depasse = bool(courant) and taille + len(ligne) + 1 > MAX_CHARS_CHUNK
        sujet_change = bool(courant) and i in coupures and taille >= MIN_CHARS_CHUNK
        if depasse or sujet_change:
            chunks.append(courant)
            reprise = [courant[-1]] if depasse and len(courant[-1]) + len(ligne) + 1 <= MAX_CHARS_CHUNK else []
            courant = reprise
            taille = sum(len(l) + 1 for l in courant)
        courant.append(ligne)
        taille += len(ligne) + 1
    if courant:
        chunks.append(courant)

    # Un dernier chunk trop court est rattaché au précédent s'il y tient
    if len(chunks) >= 2:
        dernier, avant = chunks[-1], chunks[-2]
        if sum(len(l) + 1 for l in dernier) < MIN_CHARS_CHUNK \
                and sum(len(l) + 1 for l in avant + dernier) <= MAX_CHARS_CHUNK:
            chunks[-2] = avant + dernier
            chunks.pop()

    return ["\n".join(c) for c in chunks]


def _coupures_semantiques(lignes: list[str], embed_fn: Callable[[list[str]], np.ndarray]) -> set[int]:
    """
    Indices de lignes qui OUVRENT un nouveau sujet. Pour chaque frontière entre deux lignes, on
    compare les 2 lignes AVANT à les 2 lignes APRÈS (moyenne de leurs embeddings) : comparer deux
    lignes isolées serait trop bruité, une fenêtre de 2 lignes est stable et localise précisément
    le changement. On coupe aux frontières dont la similarité est parmi les plus basses.
    """
    vecteurs = np.asarray(embed_fn(lignes), dtype="float32")
    frontieres = range(1, len(lignes))
    similarites = []
    for b in frontieres:
        gauche = vecteurs[max(0, b - 2):b].mean(axis=0)
        droite = vecteurs[b:b + 2].mean(axis=0)
        norme = float(np.linalg.norm(gauche) * np.linalg.norm(droite)) or 1.0
        similarites.append(float(gauche @ droite) / norme)
    seuil = np.percentile(similarites, PERCENTILE_COUPURE)
    return {b for b, s in zip(frontieres, similarites) if s <= seuil}


def _chunks_texte(lignes: list[str], embed_fn: Optional[Callable]) -> list[str]:
    total = sum(len(l) + 1 for l in lignes)
    if total <= MAX_CHARS_CHUNK:
        return ["\n".join(lignes)]           # document court : jamais coupé

    coupures: set[int] = set()
    if embed_fn is not None and len(lignes) >= 4:
        try:
            coupures = _coupures_semantiques(lignes, embed_fn)
        except Exception:
            coupures = set()                  # embeddings indisponibles : on coupe sur la taille seule
    return _regrouper(lignes, coupures)


# ══════════════════════════════════════════════════════════════════════════════
# Point d'entrée
# ══════════════════════════════════════════════════════════════════════════════

def decouper_document(
    texte: str,
    embed_fn: Optional[Callable[[list[str]], np.ndarray]] = None,
    ancres_fn: Optional[Callable[[str], dict]] = None,
) -> list[dict]:
    """
    Découpe le texte OCR d'UN document en chunks : [{"text", "chunk_type", "anchors"}].

    embed_fn  : fonction qui transforme une liste de lignes en matrice d'embeddings normalisés
                (optionnelle : sans elle, pas de coupure « au changement de sujet »).
    ancres_fn : fonction qui détecte les ancres financières d'un texte (revenu, charges…) ;
                le type du chunk vient de ses ancres.
    """
    lignes = [l.strip() for l in (texte or "").splitlines() if l.strip()]
    lignes = [l for l in lignes if not _RE_ENTETE_BACKEND.match(l)]
    if not lignes:
        return []

    if est_releve(lignes):
        morceaux = _morceaux_releve(lignes)
    else:
        morceaux = [(t, None) for t in _chunks_texte(lignes, embed_fn)]

    chunks = []
    for contenu, type_impose in morceaux:
        if not contenu.strip():
            continue
        ancres = ancres_fn(contenu) if ancres_fn else {}
        chunks.append({
            "text":       contenu,
            "chunk_type": type_impose or (next(iter(ancres)) if ancres else "contexte"),
            "anchors":    list(ancres.keys()),
        })
    return chunks
