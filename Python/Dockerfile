FROM python:3.11-slim

# Libs système requises par PaddleOCR / OpenCV / PyMuPDF
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgl1 \
    libglib2.0-0 \
    libgomp1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# PyTorch version CPU (pour sentence-transformers)
RUN pip install --no-cache-dir \
    torch==2.4.0 \
    --index-url https://download.pytorch.org/whl/cpu

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Pré-télécharge les modèles PaddleOCR (français + arabe) dans l'image
RUN python -c "from paddleocr import PaddleOCR; PaddleOCR(lang='fr', use_angle_cls=True); PaddleOCR(lang='ar', use_angle_cls=True)"

# Pré-télécharge le modèle d'embeddings (RAG / classification)
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2')"

COPY . .

EXPOSE 8002

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8002"]
