# services/chatbot_service.py
"""
Service RAG Chatbot CrediSense — inspiré du notebook RAG professionnel
Architecture :
  1. Chunking intelligent des documents du dossier
  2. Embeddings avec sentence-transformers
  3. Index FAISS pour recherche vectorielle
  4. Récupération top-k des chunks pertinents
  5. GROQ génère la réponse avec le contexte récupéré
"""

import json
import logging
import time
import hashlib
import re
import numpy as np
from typing import Optional
from collections import defaultdict

import faiss
from sentence_transformers import SentenceTransformer
import httpx
from tenacity import (
    retry, stop_after_attempt,
    wait_exponential, retry_if_exception_type,
    before_sleep_log
)
from config import GROQ_API_KEY, GROQ_MODEL, GROQ_API_URL

logger = logging.getLogger(__name__)

# ── Paramètres RAG ────────────────────────────────────────────────────────────
EMBEDDING_MODEL  = "paraphrase-multilingual-MiniLM-L12-v2"  # FR + AR
MAX_TOKENS_CHUNK = 300    # tokens max par chunk
EXPAND_BACK      = 1      # paragraphes contexte avant
EXPAND_FORWARD   = 2      # paragraphes contexte après
TOP_K            = 4      # chunks récupérés par question
MAX_RESPONSE_TOKENS = 400

# ── Ancres financières (adaptées de CVE/TTP → données bancaires) ──────────────
ANCHOR_PATTERNS = {
    "revenu":      re.compile(r"\b(?:salaire|revenu|net|brut|rémunération|راتب|دخل)\b", re.I),
    "charges":     re.compile(r"\b(?:charge|mensualité|loyer|remboursement|دين)\b", re.I),
    "contrat":     re.compile(r"\b(?:CDI|CDD|contrat|fonctionnaire|indépendant|عقد)\b", re.I),
    "identite":    re.compile(r"\b(?:CIN|nom|prénom|naissance|هوية|اسم)\b", re.I),
    "credit":      re.compile(r"\b(?:crédit|montant|durée|taux|endettement|قرض)\b", re.I),
    "bancaire":    re.compile(r"\b(?:solde|compte|bancaire|virement|دفع|رصيد)\b", re.I),
    "historique":  re.compile(r"\b(?:incident|historique|BON|MAUVAIS|MOYEN|سجل)\b", re.I),
}

# ── System prompt ─────────────────────────────────────────────────────────────
SYSTEM_PROMPT = """Tu es CrediSense, assistant IA expert en analyse de dossiers de crédit pour Attijariwafa Bank.
Tu réponds UNIQUEMENT en te basant sur le contexte fourni ci-dessous.
Si l'information n'est pas dans le contexte, dis-le clairement.
Sois précis, professionnel et concis. Réponds en français."""


class ChatbotService:

    def __init__(self):
        logger.info("Chargement modèle embeddings RAG...")
        self._embedding_model = SentenceTransformer(EMBEDDING_MODEL)
        self._http_client     = None
        # Cache des index FAISS par dossier
        self._indexes: dict   = {}  # dossierId → {"index": faiss, "chunks": list}
        logger.info("ChatbotService RAG prêt.")

    # ── Point d'entrée principal ──────────────────────────────────────────────

    def poser_question(
        self,
        question:   str,
        dossier_id: str,
        cin:        str,
        json_data:  Optional[dict] = None,
        ocr_textes: Optional[list] = None
    ) -> dict:
        """
        Répond à une question sur le dossier via RAG.

        Args:
            question:   question de l'agent
            dossier_id: UUID du dossier
            cin:        CIN du client
            json_data:  données structurées GROQ (depuis json_extractions)
            ocr_textes: liste de textes OCR nettoyés (depuis ocr_results)

        Returns:
            {
              "reponse":    str,
              "sources":    list,
              "statut":     str,
              "duree_ms":   float
            }
        """
        t0 = time.time()
        logger.info("RAG question — dossier=%s : %s", dossier_id, question)

        # 1. Construire ou récupérer l'index FAISS du dossier
        if dossier_id not in self._indexes:
            if not json_data and not ocr_textes:
                return self._reponse_vide()
            self._construire_index(dossier_id, json_data, ocr_textes)

        # 2. Récupérer les chunks pertinents
        chunks_pertinents = self._retriever(question, dossier_id)

        if not chunks_pertinents:
            return {
                "reponse":  "Aucune information pertinente trouvée dans le dossier.",
                "sources":  [],
                "statut":   "NO_CONTEXT",
                "duree_ms": round((time.time()-t0)*1000, 1)
            }

        # 3. Construire le contexte RAG
        contexte = self._construire_contexte(chunks_pertinents)

        # 4. Appeler GROQ avec le contexte
        reponse = self._appeler_groq(question, contexte, cin)

        sources = [
            {"chunk_id": c["chunk_id"], "type": c["chunk_type"], "score": c["score"]}
            for c in chunks_pertinents
        ]

        duree_ms = round((time.time()-t0)*1000, 1)
        logger.info("RAG réponse — %d chunks, %.0fms", len(chunks_pertinents), duree_ms)

        return {
            "reponse":  reponse,
            "sources":  sources,
            "statut":   "SUCCESS",
            "duree_ms": duree_ms
        }

    # ── Construction index FAISS ──────────────────────────────────────────────

    def _construire_index(
        self,
        dossier_id: str,
        json_data:  Optional[dict],
        ocr_textes: Optional[list]
    ):
        """Construit l'index FAISS pour un dossier."""
        logger.info("Construction index FAISS — dossier=%s", dossier_id)
        t0 = time.time()

        # 1. Générer les paragraphes depuis JSON + OCR
        paragraphes = []

        if json_data:
            paragraphes += self._json_to_paragraphes(json_data)

        if ocr_textes:
            for texte in ocr_textes:
                paragraphes += self._texte_to_paragraphes(texte)

        if not paragraphes:
            logger.warning("Aucun paragraphe pour dossier=%s", dossier_id)
            return

        # 2. Chunking intelligent (inspiré notebook RAG)
        chunks = self._chunker(paragraphes)
        logger.info("%d paragraphes → %d chunks", len(paragraphes), len(chunks))

        # 3. Générer les embeddings
        textes_embed = [self._build_embedding_text(c) for c in chunks]
        embeddings   = self._embedding_model.encode(
            textes_embed,
            convert_to_numpy=True,
            normalize_embeddings=True,
            show_progress_bar=False
        ).astype("float32")

        # 4. Index FAISS (cosine similarity via produit scalaire normalisé)
        dim   = embeddings.shape[1]
        index = faiss.IndexFlatIP(dim)
        index.add(embeddings)

        self._indexes[dossier_id] = {
            "index":  index,
            "chunks": chunks
        }

        logger.info("Index FAISS construit — %d vecteurs en %.0fms",
                   index.ntotal, (time.time()-t0)*1000)

    # ── JSON → paragraphes ────────────────────────────────────────────────────

    def _json_to_paragraphes(self, json_data: dict) -> list:
        """Convertit les données JSON structurées en paragraphes indexables."""
        groupes = {
            "identite":   ["nomClient", "prenomClient", "cin"],
            "revenus":    ["revenuMensuelNet", "typeContrat", "employeur", "anciennete"],
            "charges":    ["chargesMensuelles", "tauxEndettement", "incidentsPayment"],
            "credit":     ["montantCredit", "dureeCredit", "typeCredit"],
            "bancaire":   ["soldeMoyenCompte", "historiqueCredit"]
        }

        paragraphes = []
        for groupe, champs in groupes.items():
            lignes = []
            for champ in champs:
                val = json_data.get(champ)
                if val is not None:
                    lignes.append(f"{champ}: {val}")
            if lignes:
                paragraphes.append({
                    "para_id":    f"json_{groupe}",
                    "chunk_type": groupe,
                    "text":       "\n".join(lignes)
                })

        return paragraphes

    # ── Texte OCR → paragraphes ───────────────────────────────────────────────

    def _texte_to_paragraphes(self, texte: str) -> list:
        """Découpe le texte OCR en paragraphes avec détection d'ancres."""
        lignes = [l.strip() for l in texte.split("\n") if l.strip()]
        paragraphes = []

        for i, ligne in enumerate(lignes):
            anchors = self._detect_anchors(ligne)
            paragraphes.append({
                "para_id":    f"ocr_{i:04d}",
                "chunk_type": list(anchors.keys())[0] if anchors else "contexte",
                "text":       ligne,
                "anchors":    anchors
            })

        return paragraphes

    # ── Détection ancres financières ──────────────────────────────────────────

    def _detect_anchors(self, text: str) -> dict:
        """Détecte les ancres financières dans un paragraphe."""
        anchors = {}
        for anchor_type, pattern in ANCHOR_PATTERNS.items():
            if pattern.search(text):
                anchors[anchor_type] = True
        return anchors

    # ── Chunking intelligent ──────────────────────────────────────────────────

    def _chunker(self, paragraphes: list) -> list:
        """
        Chunking avec expansion contextuelle.
        Inspiré du notebook RAG : expand_anchor_chunks.
        """
        chunks       = []
        used_ids     = set()

        for i, para in enumerate(paragraphes):
            if para["para_id"] in used_ids:
                continue

            anchors = para.get("anchors", self._detect_anchors(para["text"]))

            # Expansion contextuelle
            start = max(0, i - EXPAND_BACK)
            end   = min(len(paragraphes), i + EXPAND_FORWARD + 1)

            textes      = []
            total_tokens = 0
            chunk_type  = para["chunk_type"]

            for j in range(start, end):
                p = paragraphes[j]
                tokens = len(p["text"]) // 4  # estimation tokens

                if total_tokens + tokens > MAX_TOKENS_CHUNK:
                    break

                textes.append(p["text"])
                used_ids.add(p["para_id"])
                total_tokens += tokens

            chunks.append({
                "chunk_id":   f"chunk_{i:04d}",
                "chunk_type": chunk_type,
                "text":       "\n".join(textes),
                "anchors":    list(anchors.keys()) if anchors else []
            })

        return chunks

    # ── Embedding text builder ────────────────────────────────────────────────

    def _build_embedding_text(self, chunk: dict) -> str:
        """Construit le texte d'embedding avec header contextuel."""
        type_concepts = {
            "identite":  "identité client CIN nom prénom",
            "revenus":   "salaire revenu mensuel emploi contrat",
            "charges":   "charges dettes mensualités endettement",
            "credit":    "crédit montant durée type consommation",
            "bancaire":  "solde compte historique incidents",
        }

        header = f"TYPE: {chunk['chunk_type']}\n"
        concept = type_concepts.get(chunk["chunk_type"], "information bancaire")
        header += f"CONCEPT: {concept}\n"

        return header + "\n" + chunk["text"]

    # ── Retriever FAISS ───────────────────────────────────────────────────────

    def _retriever(self, question: str, dossier_id: str) -> list:
        """Recherche les chunks les plus pertinents pour la question."""
        store = self._indexes.get(dossier_id)
        if not store:
            return []

        # Normaliser la question (inspiré notebook)
        question_norm = self._normaliser_question(question)

        # Encoder la question
        query_vec = self._embedding_model.encode(
            [question_norm],
            convert_to_numpy=True,
            normalize_embeddings=True
        ).astype("float32")

        # Recherche FAISS
        scores, indices = store["index"].search(query_vec, TOP_K * 2)

        results = []
        for idx, score in zip(indices[0], scores[0]):
            if idx == -1:
                continue
            chunk = store["chunks"][idx].copy()
            chunk["score"] = float(score)
            results.append(chunk)
            if len(results) >= TOP_K:
                break

        return results

    # ── Normalisation question ────────────────────────────────────────────────

    def _normaliser_question(self, question: str) -> str:
        """Enrichit la question avec des termes contextuels."""
        q = question.lower()

        if any(mot in q for mot in ["salaire", "revenu", "gagne"]):
            return q + " revenu mensuel net brut salaire"

        if any(mot in q for mot in ["endettement", "charge", "dette"]):
            return q + " taux endettement charges mensuelles"

        if any(mot in q for mot in ["crédit", "montant", "prêt"]):
            return q + " montant crédit durée mensualité"

        if any(mot in q for mot in ["contrat", "emploi", "travail"]):
            return q + " type contrat CDI CDD ancienneté employeur"

        if any(mot in q for mot in ["historique", "incident", "paiement"]):
            return q + " historique crédit incidents paiement"

        return q

    # ── Construction contexte ─────────────────────────────────────────────────

    def _construire_contexte(self, chunks: list) -> str:
        """Assemble les chunks en contexte pour GROQ."""
        parties = []
        for i, chunk in enumerate(chunks, 1):
            parties.append(
                f"[Source {i} — {chunk['chunk_type']}]\n{chunk['text']}"
            )
        return "\n\n".join(parties)

    # ── Appel GROQ ────────────────────────────────────────────────────────────

    @retry(
        stop=stop_after_attempt(3),
        wait=wait_exponential(multiplier=1, min=2, max=8),
        retry=retry_if_exception_type((httpx.TimeoutException, httpx.HTTPStatusError)),
        before_sleep=before_sleep_log(logger, logging.WARNING)
    )
    def _appeler_groq(self, question: str, contexte: str, cin: str) -> str:
        if self._http_client is None:
            self._http_client = httpx.Client(
                timeout=httpx.Timeout(25.0, connect=5.0),
                headers={
                    "Authorization": f"Bearer {GROQ_API_KEY}",
                    "Content-Type":  "application/json"
                }
            )

        user_prompt = (
            f"Contexte du dossier (CIN: {cin}) :\n\n"
            f"{contexte}\n\n"
            f"Question : {question}"
        )

        payload = {
            "model":    GROQ_MODEL,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user",   "content": user_prompt}
            ],
            "temperature": 0.3,
            "max_tokens":  MAX_RESPONSE_TOKENS
        }

        response = self._http_client.post(GROQ_API_URL, json=payload)
        response.raise_for_status()

        return response.json()["choices"][0]["message"]["content"]

    # ── Invalidation cache ────────────────────────────────────────────────────

    def invalider_index(self, dossier_id: str):
        """Supprime l'index d'un dossier (après nouveau upload)."""
        if dossier_id in self._indexes:
            del self._indexes[dossier_id]
            logger.info("Index FAISS invalidé — dossier=%s", dossier_id)

    def stats(self) -> dict:
        return {
            "dossiers_indexes": len(self._indexes),
            "model":            EMBEDDING_MODEL,
            "top_k":            TOP_K
        }

    # ── Helpers ───────────────────────────────────────────────────────────────

    def _reponse_vide(self) -> dict:
        return {
            "reponse": (
                "Je n'ai pas encore de données analysées pour ce dossier. "
                "Veuillez d'abord uploader les documents du client."
            ),
            "sources":  [],
            "statut":   "NO_DATA",
            "duree_ms": 0
        }