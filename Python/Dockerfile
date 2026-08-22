# ============================================================
# Dockerfile - Service IA CrediSense (FastAPI + OCR)
# Nécessaire car Tesseract (support arabe), Poppler et les libs
# OpenCV utilisées par Doctr ne sont PAS installées par le
# builder "code" (Oryx) d'Azure App Service - seulement pip.
# ============================================================
FROM python:3.11-slim

# Dépendances système : OCR (tesseract + langue arabe/française),
# conversion PDF (poppler) et libs graphiques requises par
# opencv-python / doctr
RUN apt-get update && apt-get install -y --no-install-recommends \
    tesseract-ocr \
    tesseract-ocr-ara \
    tesseract-ocr-fra \
    poppler-utils \
    libgl1 \
    libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 8002

# Adapte "main:app" au vrai chemin de ton objet FastAPI si besoin
# (ex: "app.main:app")
CMD ["gunicorn", "-w", "2", "-k", "uvicorn.workers.UvicornWorker", "-b", "0.0.0.0:8002", "main:app"]
