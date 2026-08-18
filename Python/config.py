import os
from dotenv import load_dotenv
from pathlib import Path

# ✅ Charge le .env depuis le dossier courant
env_path = Path(__file__).parent / '.env'
load_dotenv(dotenv_path=env_path)

GROQ_API_KEY = os.getenv("GROQ_API_KEY", "")
GROQ_MODEL   = os.getenv("GROQ_MODEL", "llama-3.3-70b-versatile")
GROQ_API_URL = os.getenv("GROQ_API_URL", "https://api.groq.com/openai/v1/chat/completions")
PORT = int(os.getenv("PORT", 8002))

# ✅ Debug — vérifiez que la clé est chargée
print(f"GROQ_API_KEY chargée: {'OUI' if GROQ_API_KEY else 'NON - PROBLÈME!'}")