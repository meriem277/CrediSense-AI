# bookworm pinned on purpose: on the newer default (trixie) base, recent Cython-compiled
# wheels (pyclipper 1.4.0, scipy 1.17) raise "zlib.error: inconsistent stream state" on import
FROM python:3.11-slim-bookworm

# Libs système requises par PaddleOCR / OpenCV / PyMuPDF
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgl1 \
    libglib2.0-0 \
    libgomp1 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# paddlepaddle loads its own zlib; with it, recent Cython wheels fail with
# "zlib.error: inconsistent stream state". Force the system zlib first.
ENV LD_PRELOAD=/lib/x86_64-linux-gnu/libz.so.1

# PyTorch version CPU (pour sentence-transformers)
RUN pip install --no-cache-dir \
    torch==2.4.0 \
    --index-url https://download.pytorch.org/whl/cpu

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Fail fast (with a clear error) if a PaddleOCR native dependency is broken
RUN python -c "import zlib, sys; print('python', sys.version.split()[0], 'zlib', zlib.ZLIB_VERSION, zlib.ZLIB_RUNTIME_VERSION)" \
 && python -c "import paddle, cv2, scipy.ndimage, sklearn; print('paddleocr native deps ok')"

# Pré-télécharge les modèles PaddleOCR 3.x (mêmes noms que services/ocr_service.py :
# détection + arabe via le pipeline, latin via TextRecognition) dans l'image
RUN python -c "from paddleocr import PaddleOCR, TextRecognition; \
PaddleOCR(text_detection_model_name='PP-OCRv5_mobile_det', text_recognition_model_name='arabic_PP-OCRv5_mobile_rec', use_doc_orientation_classify=False, use_doc_unwarping=False, use_textline_orientation=False); \
TextRecognition(model_name='latin_PP-OCRv5_mobile_rec')"

# Pré-télécharge le modèle d'embeddings (RAG / classification)
RUN python -c "from sentence_transformers import SentenceTransformer; SentenceTransformer('sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2')"

COPY . .

EXPOSE 8002

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8002"]
