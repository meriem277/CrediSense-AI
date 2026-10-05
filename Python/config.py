import os
from dotenv import load_dotenv
from pathlib import Path

# ✅ Charge le .env depuis le dossier courant
env_path = Path(__file__).parent / '.env'
load_dotenv(dotenv_path=env_path)
MISTRAL_API_KEY = os.getenv("MISTRAL_API_KEY", "")
MISTRAL_MODEL   = os.getenv("MISTRAL_MODEL", "mistral-small-latest")
MISTRAL_API_URL = os.getenv("MISTRAL_API_URL", "https://api.mistral.ai/v1/chat/completions")

GROQ_API_KEY = os.getenv("GROQ_API_KEY", "")
GROQ_MODEL   = os.getenv("GROQ_MODEL", "openai/gpt-oss-120b")
GROQ_API_URL = os.getenv("GROQ_API_URL", "https://api.groq.com/openai/v1/chat/completions")
PORT = int(os.getenv("PORT", 8002))

# ✅ Debug — vérifiez que la clé est chargée
print(f"MISTRAL_API_KEY chargée: {'OUI' if MISTRAL_API_KEY else 'NON - PROBLÈME!'}")