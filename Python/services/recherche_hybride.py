# services/recherche_hybride.py
"""
Recherche hybride pour le RAG du chatbot : SENS (embeddings) + MOTS EXACTS (BM25).

Pourquoi deux recherches :
  - les embeddings comprennent le SENS : « salaire » retrouve « NET A PAYER », en français
    comme en arabe. Mais ils sont mauvais sur les valeurs exactes : un montant (« 2 100,000 »),
    un numéro, un nom propre pèsent peu dans un vecteur de 384 nombres ;
  - BM25 est une recherche par mots (comme un moteur classique) : excellente sur les valeurs
    exactes, aveugle aux synonymes.
Les deux classements sont fusionnés par RRF (Reciprocal Rank Fusion) : un chunk bien classé
par l'une OU par l'autre remonte, un chunk bien classé par les deux gagne.

BM25 est écrit ici en Python pur (une trentaine de lignes) : aucune dépendance de plus.
"""

import math
import re
import unicodedata

import numpy as np

# Mots vides français : ils n'aident pas à départager les chunks
MOTS_VIDES = frozenset("""
le la les un une des du de d l et ou en au aux ce ces cet cette que qui quoi dont est sont etre a ont
avoir dans par pour sur avec sans son sa ses leur leurs il elle ils elles je tu nous vous quel quelle
quels quelles combien comment pourquoi quand ou y se ne pas plus moins tres cela ca ci
""".split())

_RE_MONTANT = re.compile(r"(?<![\d,.])(\d{1,3}(?:[   ]\d{3})*|\d+)(?:,(\d{1,3}))?(?![\d])")
_RE_MOT = re.compile(r"\w+", re.UNICODE)

K_RRF = 60   # constante classique de la Reciprocal Rank Fusion


def _sans_accents(texte: str) -> str:
    decompose = unicodedata.normalize("NFKD", texte)
    return "".join(c for c in decompose if not unicodedata.combining(c)).lower()


def tokeniser(texte: str) -> list[str]:
    """
    Découpe en mots, sans accents ni mots vides. Un montant « 2 100,000 » donne deux jetons :
    « 2100000 » (valeur complète) et « 2100 » (partie entière), pour que « 2100 » ou « 2 100 »
    dans la question retrouvent le chunk qui contient « 2 100,000 ».
    """
    texte = _sans_accents(texte or "")
    jetons: list[str] = []

    def montant(m: re.Match) -> str:
        entier = re.sub(r"[   ]", "", m.group(1))
        jetons.append(entier)                                  # partie entière
        if m.group(2):
            jetons.append(entier + m.group(2).ljust(3, "0"))   # valeur complète en millimes
        return " "

    texte = _RE_MONTANT.sub(montant, texte)
    jetons += [m for m in _RE_MOT.findall(texte) if m not in MOTS_VIDES and (len(m) > 1 or m.isdigit())]
    return jetons


class Bm25:
    """BM25 Okapi sur une petite collection (les chunks d'un dossier)."""

    def __init__(self, documents: list[list[str]], k1: float = 1.5, b: float = 0.75):
        self.k1, self.b = k1, b
        self.frequences = [self._compter(d) for d in documents]
        self.longueurs = np.array([len(d) for d in documents], dtype="float32")
        self.moyenne = float(self.longueurs.mean()) if len(documents) else 0.0
        n = len(documents)
        df: dict[str, int] = {}
        for freq in self.frequences:
            for mot in freq:
                df[mot] = df.get(mot, 0) + 1
        self.idf = {mot: math.log(1 + (n - nb + 0.5) / (nb + 0.5)) for mot, nb in df.items()}

    @staticmethod
    def _compter(jetons: list[str]) -> dict[str, int]:
        freq: dict[str, int] = {}
        for j in jetons:
            freq[j] = freq.get(j, 0) + 1
        return freq

    def scores(self, requete: list[str]) -> np.ndarray:
        """Score BM25 de chaque document pour la requête (0 si aucun mot en commun)."""
        resultat = np.zeros(len(self.frequences), dtype="float32")
        if not self.moyenne:
            return resultat
        for mot in set(requete):
            idf = self.idf.get(mot)
            if idf is None:
                continue
            for i, freq in enumerate(self.frequences):
                f = freq.get(mot, 0)
                if f:
                    norme = f + self.k1 * (1 - self.b + self.b * self.longueurs[i] / self.moyenne)
                    resultat[i] += idf * f * (self.k1 + 1) / norme
        return resultat


def fusion_rrf(classements: list[list[int]], k: int = K_RRF) -> dict[int, float]:
    """
    Reciprocal Rank Fusion : chaque classement (liste d'indices du meilleur au moins bon)
    donne 1 / (k + rang) à chaque élément ; les scores s'additionnent.
    """
    fusion: dict[int, float] = {}
    for classement in classements:
        for rang, indice in enumerate(classement, start=1):
            fusion[indice] = fusion.get(indice, 0.0) + 1.0 / (k + rang)
    return fusion
