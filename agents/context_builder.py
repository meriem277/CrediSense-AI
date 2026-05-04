"""
Context Builder — pont entre OCR/RAG et les agents LLM de crédit.
Se branche sur ocr/pipeline.py et rag/pipeline.py existants.
"""
#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

from dataclasses import dataclass, field
from typing import Optional
from enum import Enum
import json


# ─────────────────────────────────────────────
# Types de crédit supportés
# ─────────────────────────────────────────────

class CreditType(str, Enum):
    IMMOBILIER  = "immobilier"
    CONSOMMATION = "consommation"
    INCONNU     = "inconnu"


# ─────────────────────────────────────────────
# Structures de données
# ─────────────────────────────────────────────

@dataclass
class OcrDocument:
    """
    Un document extrait par ton ocr/pipeline.py.
    Adapte les champs à ce que ton OCR retourne réellement.
    """
    source: str                        # ex: "bulletin_salaire", "piece_identite"
    raw_text: str                      # texte brut extrait
    fields: dict = field(default_factory=dict)   # champs structurés si dispo
    confidence: float = 1.0           # score de confiance OCR (0-1)
    page_count: int = 1

    def summary(self) -> str:
        """Résumé lisible pour le prompt."""
        lines = [f"[{self.source.upper()}]"]
        if self.fields:
            for k, v in self.fields.items():
                lines.append(f"  {k}: {v}")
        else:
            # Tronque le texte brut si pas de champs structurés
            preview = self.raw_text[:400].replace("\n", " ").strip()
            lines.append(f"  Contenu: {preview}{'...' if len(self.raw_text) > 400 else ''}")
        if self.confidence < 0.8:
            lines.append(f"  ⚠ Confiance OCR faible: {self.confidence:.0%}")
        return "\n".join(lines)


@dataclass
class RagResult:
    """
    Un résultat retourné par ton rag/retriever.py.
    Adapte les champs à ce que ton RAG retourne réellement.
    """
    content: str          # texte de la règle / réglementation
    source: str           # ex: "regles_internes", "loi_credit_conso"
    score: float = 1.0    # score de similarité (0-1)
    metadata: dict = field(default_factory=dict)

    def summary(self) -> str:
        return f"[{self.source}] {self.content}"


@dataclass
class ClientContext:
    """
    Contexte complet d'un client : ce qu'on sait de lui
    avant même d'analyser les documents.
    """
    client_id: Optional[str] = None
    credit_type: CreditType = CreditType.INCONNU
    montant_demande: Optional[float] = None
    duree_mois: Optional[int] = None
    extra: dict = field(default_factory=dict)   # tout autre champ utile


# ─────────────────────────────────────────────
# Context Builder principal
# ─────────────────────────────────────────────

class ContextBuilder:
    """
    Prend les outputs de ocr/pipeline.py + rag/pipeline.py
    et produit un prompt structuré prêt pour un agent LLM.
    """

    # Pièces requises par type de crédit
    REQUIRED_DOCS = {
        CreditType.IMMOBILIER: [
            "piece_identite",
            "justificatif_domicile",
            "bulletin_salaire",
            "avis_imposition",
            "releve_bancaire",
            "compromis_vente",
            "justificatif_apport",
        ],
        CreditType.CONSOMMATION: [
            "piece_identite",
            "justificatif_domicile",
            "bulletin_salaire",
            "avis_imposition",
            "releve_bancaire",
        ],
        CreditType.INCONNU: [],
    }

    def __init__(
        self,
        ocr_documents: list[OcrDocument],
        rag_results: list[RagResult],
        client_context: ClientContext,
    ):
        self.ocr_documents   = ocr_documents
        self.rag_results     = rag_results
        self.client_context  = client_context

    # ── Helpers internes ──────────────────────

    def _detect_credit_type(self) -> CreditType:
        """
        Si le type n'est pas fourni, tente de le déduire
        des documents présents.
        """
        if self.client_context.credit_type != CreditType.INCONNU:
            return self.client_context.credit_type

        sources = {doc.source.lower() for doc in self.ocr_documents}
        if any(s in sources for s in ["compromis_vente", "acte_notarie", "plan_financement"]):
            return CreditType.IMMOBILIER
        if any(s in sources for s in ["bon_commande", "facture_pro_forma"]):
            return CreditType.CONSOMMATION
        return CreditType.INCONNU

    def _find_missing_docs(self, credit_type: CreditType) -> list[str]:
        """Compare les docs reçus aux docs requis."""
        received = {doc.source.lower() for doc in self.ocr_documents}
        required = self.REQUIRED_DOCS.get(credit_type, [])
        return [doc for doc in required if doc not in received]

    def _ocr_section(self) -> str:
        if not self.ocr_documents:
            return "Aucun document fourni."
        return "\n\n".join(doc.summary() for doc in self.ocr_documents)

    def _rag_section(self) -> str:
        if not self.rag_results:
            return "Aucune règle métier récupérée."
        # Filtre les résultats peu pertinents
        relevant = [r for r in self.rag_results if r.score >= 0.5]
        return "\n".join(r.summary() for r in relevant)

    def _client_section(self) -> str:
        ctx = self.client_context
        lines = []
        if ctx.client_id:
            lines.append(f"ID client       : {ctx.client_id}")
        if ctx.credit_type != CreditType.INCONNU:
            lines.append(f"Type de crédit  : {ctx.credit_type.value}")
        if ctx.montant_demande:
            lines.append(f"Montant demandé : {ctx.montant_demande:,.0f} DT")
        if ctx.duree_mois:
            lines.append(f"Durée souhaitée : {ctx.duree_mois} mois")
        for k, v in ctx.extra.items():
            lines.append(f"{k}: {v}")
        return "\n".join(lines) if lines else "Pas d'informations client supplémentaires."

    def _missing_docs_section(self, credit_type: CreditType, missing: list[str]) -> str:
        if not missing:
            return "Dossier complet — toutes les pièces requises sont présentes."
        items = "\n".join(f"  - {doc.replace('_', ' ')}" for doc in missing)
        return f"Pièces manquantes ({len(missing)}):\n{items}"

    # ── Méthode principale ────────────────────

    def build(self) -> str:
        """
        Retourne le prompt complet à envoyer à l'agent LLM.
        """
        credit_type = self._detect_credit_type()
        missing     = self._find_missing_docs(credit_type)

        sections = [
            ("INFORMATIONS CLIENT", self._client_section()),
            ("DOCUMENTS ANALYSÉS (OCR)", self._ocr_section()),
            ("RÈGLES MÉTIER APPLICABLES (RAG)", self._rag_section()),
            ("ÉTAT DU DOSSIER", self._missing_docs_section(credit_type, missing)),
        ]

        prompt = ""
        for title, content in sections:
            prompt += f"## {title}\n{content}\n\n"

        prompt += (
            "## MISSION\n"
            f"Analyser ce dossier de crédit {credit_type.value}. "
            "Évaluer la complétude, la cohérence des informations, "
            "et formuler une recommandation motivée.\n"
            "Réponds UNIQUEMENT en JSON avec les clés :\n"
            "  - statut        : 'complet' | 'incomplet' | 'refus'\n"
            "  - pieces_manquantes : liste des pièces à demander\n"
            "  - observations  : liste de points importants relevés\n"
            "  - recommandation: texte court à destination du conseiller\n"
        )
        return prompt

    def build_meta(self) -> dict:
        """
        Retourne les métadonnées utiles (pour logs, debug, front).
        """
        credit_type = self._detect_credit_type()
        missing     = self._find_missing_docs(credit_type)
        return {
            "credit_type"      : credit_type.value,
            "docs_received"    : [d.source for d in self.ocr_documents],
            "docs_missing"     : missing,
            "rag_results_count": len(self.rag_results),
            "dossier_complet"  : len(missing) == 0,
        }


# ─────────────────────────────────────────────
# Fonction utilitaire d'intégration
# ─────────────────────────────────────────────

def build_context_from_pipelines(
    ocr_output,       # output de ton ocr/pipeline.py  (dict ou list)
    rag_output,       # output de ton rag/pipeline.py  (dict ou list)
    client_info: dict = None,
) -> tuple[str, dict]:
    """
    Point d'entrée principal.
    Adapte les outputs bruts de tes pipelines existants.

    Retourne : (prompt_str, meta_dict)
    """
    client_info = client_info or {}

    # ── Adapter l'output OCR ──────────────────
    # Ton ocr/pipeline.py retourne probablement une liste de dicts
    # ou un seul dict. On normalise ici.
    if isinstance(ocr_output, dict):
        ocr_output = [ocr_output]

    ocr_docs = []
    for item in (ocr_output or []):
        ocr_docs.append(OcrDocument(
            source     = item.get("source", item.get("type", "document_inconnu")),
            raw_text   = item.get("text", item.get("raw_text", item.get("content", ""))),
            fields     = item.get("fields", item.get("extracted_fields", {})),
            confidence = item.get("confidence", item.get("score", 1.0)),
            page_count = item.get("page_count", item.get("pages", 1)),
        ))

    # ── Adapter l'output RAG ─────────────────
    if isinstance(rag_output, dict):
        rag_output = rag_output.get("results", rag_output.get("documents", [rag_output]))

    rag_results = []
    for item in (rag_output or []):
        rag_results.append(RagResult(
            content  = item.get("content", item.get("text", item.get("page_content", ""))),
            source   = item.get("source", item.get("metadata", {}).get("source", "base_connaissance")),
            score    = item.get("score",  item.get("similarity", item.get("relevance_score", 1.0))),
            metadata = item.get("metadata", {}),
        ))

    # ── Contexte client ──────────────────────
    credit_type_str = client_info.get("credit_type", "inconnu").lower()
    try:
        credit_type = CreditType(credit_type_str)
    except ValueError:
        credit_type = CreditType.INCONNU

    ctx = ClientContext(
        client_id      = client_info.get("client_id"),
        credit_type    = credit_type,
        montant_demande= client_info.get("montant"),
        duree_mois     = client_info.get("duree_mois"),
        extra          = {k: v for k, v in client_info.items()
                         if k not in ("client_id", "credit_type", "montant", "duree_mois")},
    )

    builder = ContextBuilder(ocr_docs, rag_results, ctx)
    return builder.build(), builder.build_meta()