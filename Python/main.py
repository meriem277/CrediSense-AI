import logging
import uvicorn
import tempfile
import os
from fastapi import FastAPI, HTTPException, UploadFile, File, Form
from pydantic import BaseModel
from typing import Optional
from config import PORT
from services.nlp_service     import NLPClassifier, verifier_documents_requis
from services.ocr_service     import OcrService
from services.groq_service    import GroqService
from services.chatbot_service import ChatbotService
from services.agent_service   import AgentService
from services.document_classifier_service import DocumentClassifierService  # ✅ nouveau

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(
    title="CrediSense AI Service",
    description="OCR · NLP · GROQ · RAG — Pipeline IA complet",
    version="3.0.0"
)

# ── Initialisation ────────────────────────────────────────────────────────────
classifier            = NLPClassifier()
ocr_service            = OcrService()
groq_service            = GroqService()
chatbot_service        = ChatbotService()
agent_service            = AgentService()
document_classifier    = DocumentClassifierService()  # ✅ nouveau — cascade embeddings + LLM

# ══════════════════════════════════════════════════════════════════════════════
# SCHEMAS
# ══════════════════════════════════════════════════════════════════════════════

class ClassifyRequest(BaseModel):
    texte:      str
    dossier_id: Optional[str] = None   # ✅ remplace seuil_confiance (géré en interne par label maintenant)

class ClassifyHybridRequest(BaseModel):   # ✅ nouveau
    texte:      str
    dossier_id: Optional[str] = None

class VerifyDocumentsRequest(BaseModel):
    documents_fournis: list[str]
    age_client:        Optional[int]  = None
    type_contrat:      Optional[str]  = None
    nationalite:       Optional[str]  = "TN"

class OcrPathRequest(BaseModel):
    pdf_path:      str
    type_original: str = "pdf"

class GroqExtractRequest(BaseModel):
    texte_nettoye: str
    cin:           str = ""

class ChatRequest(BaseModel):
    question:   str
    dossier_id: str
    cin:        str            = ""
    json_data:  Optional[dict] = None
    ocr_textes: Optional[list] = None

class IndexRequest(BaseModel):
    dossier_id: str
    cin:        str       = ""
    ocr_textes: list[str]

class AgentConsommationRequest(BaseModel):
    document_text: str

# ══════════════════════════════════════════════════════════════════════════════
# HEALTH
# ══════════════════════════════════════════════════════════════════════════════

@app.get("/health")
def health():
    return {
        "status":  "ok",
        "service": "CrediSense AI",
        "version": "3.0.0",
        "modules": {
            "nlp":                "ready",
            "nlp_hybrid":        "ready",   # ✅ nouveau
            "ocr":                "ready" if ocr_service._doctr_model else "pymupdf-only",
            "groq":                "ready",
            "chatbot":            f"ready ({chatbot_service.stats()['dossiers_indexes']} indexes)",
            "agent":            "ready"
        }
    }

# ══════════════════════════════════════════════════════════════════════════════
# NLP
# ══════════════════════════════════════════════════════════════════════════════

@app.post("/ai/classify")
def classify_document(request: ClassifyRequest):
    """Classification par embeddings uniquement (rapide, pas de fallback LLM)."""
    try:
        return classifier.classify(
            texte=request.texte,
            dossier_id=request.dossier_id
        )
    except Exception as e:
        logger.error(f"Erreur /ai/classify : {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/ai/classify-hybrid")   # ✅ nouveau endpoint
def classify_document_hybrid(request: ClassifyHybridRequest):
    """
    Classification en cascade : embeddings d'abord (rapide), bascule
    automatique vers le LLM GROQ si le score est en zone grise.
    Champ "methode" dans la réponse indique laquelle a tranché.
    """
    try:
        return document_classifier.classify(
            texte_ocr=request.texte,
            dossier_id=request.dossier_id
        )
    except Exception as e:
        logger.error(f"Erreur /ai/classify-hybrid : {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/ai/verify-documents")
def verify_documents(request: VerifyDocumentsRequest):
    try:
        return verifier_documents_requis(
            documents_fournis=request.documents_fournis,
            age_client=request.age_client,
            type_contrat=request.type_contrat,
            nationalite=request.nationalite
        )
    except Exception as e:
        logger.error(f"Erreur /ai/verify-documents : {e}")
        raise HTTPException(status_code=500, detail=str(e))

# ══════════════════════════════════════════════════════════════════════════════
# OCR
# ══════════════════════════════════════════════════════════════════════════════

@app.post("/ocr")
async def ocr_upload(
    file:          UploadFile = File(...),
    type_original: str        = Form(default="pdf")
):
    try:
        pdf_bytes = await file.read()
        with tempfile.NamedTemporaryFile(delete=False, suffix=".pdf") as tmp:
            tmp.write(pdf_bytes)
            tmp_path = tmp.name

        result = ocr_service.extraire(tmp_path, type_original)
        os.unlink(tmp_path)

        return {
            "texte":      result["texte"],
            "nbPages":    result["nb_pages"],
            "confidence": result["confidence"],
            "statut":     result["statut"],
            "erreur":     result.get("erreur"),
            "cas":        result["cas"],
            "duree_ms":   result.get("duree_ms", 0)
        }
    except Exception as e:
        logger.error(f"Erreur /ocr : {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/ocr/extract")
def ocr_extract(request: OcrPathRequest):
    try:
        result = ocr_service.extraire(request.pdf_path, request.type_original)
        return {
            "texte":      result["texte"],
            "nbPages":    result["nb_pages"],
            "confidence": result["confidence"],
            "statut":     result["statut"],
            "erreur":     result.get("erreur"),
            "cas":        result["cas"],
            "duree_ms":   result.get("duree_ms", 0)
        }
    except Exception as e:
        logger.error(f"Erreur /ocr/extract : {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/ocr/stats")
def ocr_stats():
    return ocr_service.stats()

@app.delete("/ocr/cache")
def vider_cache_ocr():
    ocr_service._vider_cache()
    return {"message": "Cache OCR vidé"}

# ══════════════════════════════════════════════════════════════════════════════
# GROQ — Extraction JSON
# ══════════════════════════════════════════════════════════════════════════════

@app.post("/ai/extract-json")
def extraire_json(request: GroqExtractRequest):
    try:
        return groq_service.extraire_json(
            texte_nettoye=request.texte_nettoye,
            cin=request.cin
        )
    except Exception as e:
        logger.error(f"Erreur /ai/extract-json : {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/ai/extract-json/stats")
def stats_groq():
    return groq_service.stats()

@app.delete("/ai/extract-json/cache")
def vider_cache_groq():
    groq_service.vider_cache()
    return {"message": "Cache GROQ vidé"}

# ══════════════════════════════════════════════════════════════════════════════
# RAG CHATBOT
# ══════════════════════════════════════════════════════════════════════════════

@app.post("/ai/chat")
def poser_question(request: ChatRequest):
    try:
        return chatbot_service.poser_question(
            question=request.question,
            dossier_id=request.dossier_id,
            cin=request.cin,
            json_data=request.json_data,
            ocr_textes=request.ocr_textes
        )
    except Exception as e:
        logger.error(f"Erreur /ai/chat : {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/ai/chat/index")
def indexer_dossier(request: IndexRequest):
    """Indexe tous les textes OCR dans FAISS sans poser de question."""
    try:
        logger.info(f"Indexation — {len(request.ocr_textes)} textes")
        for i, t in enumerate(request.ocr_textes):
            logger.info(f"Texte {i+1} — {len(t)} chars : {t[:100]}...")
        chatbot_service.invalider_index(request.dossier_id)

        texte_complet = "\n\n".join([
            f"=== Document {i+1} ===\n{t}"
            for i, t in enumerate(request.ocr_textes)
            if t and t.strip()
        ])

        if not texte_complet.strip():
            return {"status": "empty", "message": "Aucun texte a indexer"}

        chatbot_service.poser_question(
            question   = "Analyse ce dossier de credit",
            dossier_id = request.dossier_id,
            cin        = request.cin,
            ocr_textes = [texte_complet]
        )

        logger.info(f"Index cree — {len(request.ocr_textes)} docs, {len(texte_complet)} chars")

        return {
            "status":     "indexed",
            "dossier_id": request.dossier_id,
            "nb_textes":  len(request.ocr_textes),
            "nb_chars":   len(texte_complet)
        }
    except Exception as e:
        logger.error(f"Erreur /ai/chat/index : {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.delete("/ai/chat/index/{dossier_id}")
def invalider_index(dossier_id: str):
    chatbot_service.invalider_index(dossier_id)
    return {"message": f"Index invalide — dossier {dossier_id}"}

@app.get("/ai/chat/stats")
def stats_chatbot():
    return chatbot_service.stats()

# ══════════════════════════════════════════════════════════════════════════════
# AGENT — Score consommation
# ══════════════════════════════════════════════════════════════════════════════

@app.post("/ai/score/consommation")
def analyser_consommation(request: AgentConsommationRequest):
    try:
        return agent_service.analyser_consommation(request.document_text)
    except Exception as e:
        logger.error(f"Erreur /ai/score/consommation : {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/ai/score/stats")
def stats_agent():
    return agent_service.stats()

# ══════════════════════════════════════════════════════════════════════════════
# LANCEMENT
# ══════════════════════════════════════════════════════════════════════════════

if __name__ == "__main__":
    uvicorn.run("main:app", host="0.0.0.0", port=PORT, reload=True)