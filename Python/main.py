import logging
import uvicorn
import tempfile
import os
from pathlib import Path
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
from services.controle_type   import evaluer_type

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

MAX_UPLOAD_OCTETS = 25 * 1024 * 1024   # 25 Mo par fichier envoyé à /ocr

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
    texte:        str
    dossier_id:   Optional[str] = None
    type_declare: Optional[str] = None   # type choisi par le client : comparé au type détecté

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
    cin:           str            = ""
    type_document: Optional[str]  = None   # ✅ nouveau — permet un score de confiance contextuel

class ChatRequest(BaseModel):
    question:   str
    dossier_id: str
    cin:        str            = ""
    json_data:  Optional[dict] = None
    ocr_textes: Optional[list] = None
    historique: Optional[list] = None   # derniers messages [{"role": "user|assistant", "content": "..."}]

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
    Le champ "controle" compare le type détecté au type déclaré (voir controle_type.py).
    """
    try:
        resultat = document_classifier.classify(
            texte_ocr=request.texte,
            dossier_id=request.dossier_id
        )
        return {**resultat, "controle": evaluer_type(request.type_declare, resultat)}
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
    tmp_path = None
    try:
        contenu = await file.read()
        if len(contenu) > MAX_UPLOAD_OCTETS:
            raise HTTPException(
                status_code=413,
                detail=f"Fichier trop volumineux (maximum {MAX_UPLOAD_OCTETS // (1024 * 1024)} Mo)"
            )

        # Le format réel est détecté par le service OCR à partir du contenu ;
        # le suffixe ne sert qu'à nommer le fichier temporaire.
        suffixe = Path(file.filename or "").suffix.lower()[:10] or ".bin"
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffixe) as tmp:
            tmp.write(contenu)
            tmp_path = tmp.name

        result = ocr_service.extraire(tmp_path, type_original)

        return {
            "texte":      result["texte"],
            "nbPages":    result["nb_pages"],
            "confidence": result["confidence"],
            "statut":     result["statut"],
            "erreur":     result.get("erreur"),
            "cas":        result["cas"],
            "duree_ms":   result.get("duree_ms", 0)
        }
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Erreur /ocr : {e}")
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        if tmp_path and os.path.exists(tmp_path):
            os.unlink(tmp_path)

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
            cin=request.cin,
            type_document=request.type_document   # ✅ propagé pour la confiance contextuelle
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
            ocr_textes=request.ocr_textes,
            historique=request.historique
        )
    except Exception as e:
        logger.error(f"Erreur /ai/chat : {e}")
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/ai/chat/index")
def indexer_dossier(request: IndexRequest):
    """
    Indexe les textes OCR dans FAISS, SANS appeler le LLM.

    (Avant : l'index était construit en posant une vraie question au LLM, dont la
    réponse était jetée — un appel gaspillé qui consommait les limites de débit.)
    Chaque texte est un document distinct, étiqueté séparément pour la recherche.
    Les contenus des documents ne sont pas écrits dans les journaux (données personnelles).
    """
    try:
        textes = [t for t in request.ocr_textes if t and t.strip()]
        logger.info(f"Indexation — dossier={request.dossier_id}, {len(textes)} documents")

        if not textes:
            chatbot_service.invalider_index(request.dossier_id)
            return {"status": "empty", "message": "Aucun texte a indexer"}

        resultat  = chatbot_service.indexer_documents(request.dossier_id, textes)
        nb_chars  = sum(len(t) for t in textes)
        logger.info(f"Index cree — {len(textes)} docs, {nb_chars} chars, {resultat['nb_chunks']} chunks")

        return {
            "status":     "indexed",
            "dossier_id": request.dossier_id,
            "nb_textes":  len(textes),
            "nb_chars":   nb_chars,
            "nb_chunks":  resultat["nb_chunks"]
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