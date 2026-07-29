import logging
import uvicorn
import tempfile
import os
import time
from fastapi import FastAPI, HTTPException, UploadFile, File, Form
from pydantic import BaseModel
from typing import Optional
from config import PORT
from services.nlp_service    import NLPClassifier, verifier_documents_requis
from services.ocr_service    import OcrService
from services.groq_service   import GroqService
from services.chatbot_service import ChatbotService
from services.agent_service import AgentService

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

app = FastAPI(
    title="CrediSense AI Service",
    description="OCR · NLP · GROQ · RAG — Pipeline IA complet",
    version="3.0.0"
)

# ── Initialisation au démarrage ───────────────────────────────────────────────
classifier      = NLPClassifier()    # NLP classification
ocr_service     = OcrService()       # OCR PyMuPDF + Doctr
groq_service    = GroqService()      # GROQ extraction JSON
chatbot_service = ChatbotService()   # RAG chatbot FAISS
agent_service = AgentService()

# ══════════════════════════════════════════════════════════════════════════════
# SCHEMAS
# ══════════════════════════════════════════════════════════════════════════════

class ClassifyRequest(BaseModel):
    texte:           str
    seuil_confiance: Optional[float] = 0.35

class VerifyDocumentsRequest(BaseModel):
    documents_fournis: list[str]
    age_client:        Optional[int] = None
    type_contrat:      Optional[str] = None
    nationalite:       Optional[str] = "TN"

class OcrPathRequest(BaseModel):
    pdf_path:      str
    type_original: str = "pdf"

class GroqExtractRequest(BaseModel):
    texte_nettoye: str
    cin:           str = ""

class ChatRequest(BaseModel):
    question:   str
    dossier_id: str
    cin:        str = ""
    json_data:  Optional[dict] = None
    ocr_textes: Optional[list] = None

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
            "nlp":     "ready",
            "ocr":     "ready" if ocr_service._doctr_model else "pymupdf-only",
            "groq":    "ready",
            "chatbot": f"ready ({chatbot_service.stats()['dossiers_indexes']} indexes)",
            "agent":   "ready"
        }
    }

# ══════════════════════════════════════════════════════════════════════════════
# NLP — Classification + Vérification
# ══════════════════════════════════════════════════════════════════════════════

@app.post("/ai/classify")
def classify_document(request: ClassifyRequest):
    """Classifie un document bancaire depuis son texte OCR."""
    try:
        return classifier.classify(
            texte=request.texte,
            seuil_confiance=request.seuil_confiance
        )
    except Exception as e:
        logger.error(f"Erreur /ai/classify : {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/ai/verify-documents")
def verify_documents(request: VerifyDocumentsRequest):
    """Vérifie la complétude du dossier selon les règles métier."""
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
# OCR — Extraction texte (compatible Doctrclientservice.java)
# ══════════════════════════════════════════════════════════════════════════════

@app.post("/ocr")
async def ocr_upload(
    file:          UploadFile = File(...),
    type_original: str        = Form(default="pdf")
):
    """Upload multipart → texte OCR. Compatible Doctrclientservice.java."""
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
    """Chemin fichier → texte OCR. Appel interne depuis Spring Boot."""
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
# GROQ — Extraction JSON structurée
# ══════════════════════════════════════════════════════════════════════════════

@app.post("/ai/extract-json")
def extraire_json(request: GroqExtractRequest):
    """Texte OCR → JSON financier structuré. Remplace GroqService.java."""
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
# RAG CHATBOT — Questions sur le dossier
# ══════════════════════════════════════════════════════════════════════════════

@app.post("/ai/chat")
def poser_question(request: ChatRequest):
    """RAG Chatbot — répond à une question sur le dossier. Remplace ChatbotService.java."""
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

@app.delete("/ai/chat/index/{dossier_id}")
def invalider_index(dossier_id: str):
    chatbot_service.invalider_index(dossier_id)
    return {"message": f"Index invalidé — dossier {dossier_id}"}

@app.get("/ai/chat/stats")
def stats_chatbot():
    return chatbot_service.stats()
# ══════════════════════════════════════════════════════════════════════════════
# Agents
# ══════════════════════════════════════════════════════════════════════════════

@app.post("/ai/score/consommation")
def analyser_consommation(request: AgentConsommationRequest):
    """
    Agent IA crédit consommation.
    Remplace ConsommationAgentService.java.
    """
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