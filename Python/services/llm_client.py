# services/llm_client.py
"""
Routeur LLM CrediSense — plusieurs fournisseurs avec bascule automatique.

Fournisseurs supportés :
  - mistral : API Mistral (cloud)
  - gemini     : API Google Gemini (cloud, offre gratuite sans carte — modèles Flash)
  - openrouter : API OpenRouter (cloud, modèles gratuits suffixés ":free")
  - groq       : API Groq (cloud)
  - ollama     : modèle local via Ollama (aucun quota, dépend de votre machine)

Principe :
  - Chaque tâche (extraction, analyse, chat) a une ROUTE = liste ordonnée de fournisseurs.
  - Sur un 429, le fournisseur est mis en pause (Retry-After) et l'appel bascule
    IMMÉDIATEMENT sur le suivant, au lieu d'attendre et d'échouer.
  - Chaque fournisseur a son propre limiteur de débit, partagé par tous les services.
  - Un fournisseur sans clé API (ou Ollama éteint) est simplement ignoré.

Configuration (.env) — toutes optionnelles :
  LLM_ROUTE_CLASSIFICATION=gemini,mistral,openrouter
  LLM_ROUTE_EXTRACTION=gemini,mistral,openrouter
  LLM_ROUTE_ANALYSE=mistral,gemini,openrouter
  LLM_ROUTE_CHAT=mistral,gemini,openrouter
  GEMINI_API_KEY=...          GEMINI_MODEL=gemini-flash-latest
  OPENROUTER_API_KEY=...      OPENROUTER_MODEL=<nom du modèle>:free
  GROQ_API_KEY=...            GROQ_MODEL=llama-3.3-70b-versatile
  OLLAMA_URL=http://localhost:11434/api/chat
  OLLAMA_MODEL=qwen2.5:7b     OLLAMA_NUM_CTX=4096     OLLAMA_TIMEOUT=240
  MISTRAL_MIN_INTERVAL=1.2    GROQ_MIN_INTERVAL=2.0
  LLM_MAX_ATTENTE=30          LLM_TENTATIVES_PAR_PROVIDER=2
"""

import os
import time
import logging
import threading
from dataclasses import dataclass
from typing import Optional

import httpx

from config import MISTRAL_API_KEY, MISTRAL_MODEL, MISTRAL_API_URL

logger = logging.getLogger(__name__)

# Charge le .env explicitement : config.py ne charge pas forcément
# les variables GROQ_*, GEMINI_*, LLM_ROUTE_*...
try:
    from pathlib import Path
    from dotenv import load_dotenv
    _ENV_PATH = Path(__file__).resolve().parent.parent / ".env"   # Python/.env
    load_dotenv(_ENV_PATH, override=True)
except ImportError:
    logger.warning("python-dotenv absent — variables lues uniquement depuis l'environnement")


# ── Erreur levée quand aucun fournisseur n'a pu répondre ─────────────────────
class LLMUnavailableError(RuntimeError):
    pass


# ── Réponse normalisée, quel que soit le fournisseur ─────────────────────────
@dataclass
class LLMResponse:
    content:           str
    provider:          str
    model:             str
    duree_ms:          float
    prompt_tokens:     int = 0
    completion_tokens: int = 0


# ── Configuration des fournisseurs ───────────────────────────────────────────
@dataclass
class ProviderConfig:
    name:          str
    url:           str
    api_key:       str
    model:         str
    min_interval:  float
    timeout:       float
    native_ollama: bool = False
    json_mode_ok:  bool = True   # le fournisseur accepte response_format=json_object

    @property
    def configure(self) -> bool:
        if self.native_ollama:
            return True
        return bool(self.api_key) and bool(self.model)


_PROVIDERS = {
    "mistral": ProviderConfig(
        name="mistral",
        url=MISTRAL_API_URL,
        api_key=MISTRAL_API_KEY or "",
        model=MISTRAL_MODEL,
        min_interval=float(os.getenv("MISTRAL_MIN_INTERVAL", "1.2")),
        timeout=60.0,
    ),
    "gemini": ProviderConfig(
        name="gemini",
        # Endpoint Gemini compatible OpenAI
        url=os.getenv("GEMINI_API_URL",
                      "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"),
        api_key=os.getenv("GEMINI_API_KEY", ""),
        model=os.getenv("GEMINI_MODEL", "gemini-flash-latest"),
        min_interval=float(os.getenv("GEMINI_MIN_INTERVAL", "4.0")),
        timeout=60.0,
    ),
    "openrouter": ProviderConfig(
        name="openrouter",
        url=os.getenv("OPENROUTER_API_URL", "https://openrouter.ai/api/v1/chat/completions"),
        api_key=os.getenv("OPENROUTER_API_KEY", ""),
        model=os.getenv("OPENROUTER_MODEL", ""),
        min_interval=float(os.getenv("OPENROUTER_MIN_INTERVAL", "3.0")),
        timeout=90.0,
        json_mode_ok=False,   # beaucoup de modèles gratuits refusent response_format
    ),
    "groq": ProviderConfig(
        name="groq",
        url=os.getenv("GROQ_API_URL", "https://api.groq.com/openai/v1/chat/completions"),
        api_key=os.getenv("GROQ_API_KEY", ""),
        model=os.getenv("GROQ_MODEL", "llama-3.3-70b-versatile"),
        min_interval=float(os.getenv("GROQ_MIN_INTERVAL", "2.0")),
        timeout=60.0,
    ),
    "ollama": ProviderConfig(
        name="ollama",
        url=os.getenv("OLLAMA_URL", "http://localhost:11434/api/chat"),
        api_key="",
        model=os.getenv("OLLAMA_MODEL", "qwen2.5:7b"),
        min_interval=0.0,
        timeout=float(os.getenv("OLLAMA_TIMEOUT", "240")),
        native_ollama=True,
    ),
}

OLLAMA_NUM_CTX              = int(os.getenv("OLLAMA_NUM_CTX", "4096"))
LLM_MAX_ATTENTE             = float(os.getenv("LLM_MAX_ATTENTE", "30"))
MAX_TENTATIVES_PAR_PROVIDER = int(os.getenv("LLM_TENTATIVES_PAR_PROVIDER", "2"))

_DEFAULT_ROUTES = {
    "classification": "gemini,mistral,openrouter",
    "extraction":     "gemini,mistral,openrouter",
    "analyse":        "mistral,gemini,openrouter",
    "chat":           "mistral,gemini,openrouter",
}


def _route(task: str) -> list[str]:
    brut = os.getenv(f"LLM_ROUTE_{task.upper()}", _DEFAULT_ROUTES.get(task, "mistral,gemini,openrouter"))
    return [p.strip().lower() for p in brut.split(",") if p.strip()]


# ── État par fournisseur : limiteur de débit + pause après 429 ───────────────
class _ProviderState:

    def __init__(self, min_interval: float):
        self.min_interval   = min_interval
        self._lock          = threading.Lock()
        self._next_allowed  = 0.0
        self.cooldown_until = 0.0

    def disponible(self) -> bool:
        return time.monotonic() >= self.cooldown_until

    def attente_restante(self) -> float:
        return max(0.0, self.cooldown_until - time.monotonic())

    def wait(self) -> None:
        with self._lock:
            now   = time.monotonic()
            delai = self._next_allowed - now
            if delai > 0:
                time.sleep(delai)
                now = time.monotonic()
            self._next_allowed = now + self.min_interval

    def mettre_en_pause(self, secondes: float) -> None:
        with self._lock:
            fin = time.monotonic() + secondes
            self.cooldown_until = max(self.cooldown_until, fin)
            self._next_allowed  = max(self._next_allowed, fin)


_STATES = {nom: _ProviderState(cfg.min_interval) for nom, cfg in _PROVIDERS.items()}

# Affiche au démarrage ce qui est réellement configuré (aide au diagnostic)
for _t in ("classification", "extraction", "analyse", "chat"):
    _actifs = [n for n in _route(_t) if n in _PROVIDERS and _PROVIDERS[n].configure]
    logger.warning("Route LLM '%s' : %s", _t, " → ".join(_actifs) or "AUCUN fournisseur configuré")
_client = httpx.Client()


# ── Envoi HTTP selon le fournisseur ──────────────────────────────────────────
def _envoyer(cfg: ProviderConfig, messages: list, temperature: float,
             max_tokens: int, json_mode: bool) -> httpx.Response:
    timeout = httpx.Timeout(cfg.timeout, connect=5.0)

    if cfg.native_ollama:
        # API native Ollama : permet de fixer num_ctx (les prompts CrediSense sont longs)
        payload = {
            "model":    cfg.model,
            "messages": messages,
            "stream":   False,
            "options": {
                "temperature": temperature,
                "num_predict": max_tokens,
                "num_ctx":     OLLAMA_NUM_CTX,
            },
        }
        if json_mode:
            payload["format"] = "json"
        return _client.post(cfg.url, json=payload, timeout=timeout)

    # API compatible OpenAI (Mistral, Groq)
    payload = {
        "model":       cfg.model,
        "messages":    messages,
        "temperature": temperature,
        "max_tokens":  max_tokens,
    }
    if json_mode and cfg.json_mode_ok:
        payload["response_format"] = {"type": "json_object"}
    # Modèles gpt-oss sur Groq : raisonnement court → réponses plus rapides,
    # et les tokens de raisonnement ne "mangent" plus la réponse finale
    if cfg.name == "groq" and "gpt-oss" in cfg.model:
        payload["reasoning_effort"] = os.getenv("GROQ_REASONING_EFFORT", "low")
    return _client.post(
        cfg.url,
        json=payload,
        headers={"Authorization": f"Bearer {cfg.api_key}", "Content-Type": "application/json"},
        timeout=timeout,
    )


def _extraire_contenu(cfg: ProviderConfig, data: dict) -> tuple[str, int, int]:
    if cfg.native_ollama:
        return (
            data["message"]["content"],
            data.get("prompt_eval_count", 0),
            data.get("eval_count", 0),
        )
    usage = data.get("usage", {}) or {}
    return (
        data["choices"][0]["message"]["content"],
        usage.get("prompt_tokens", 0),
        usage.get("completion_tokens", 0),
    )


def _retry_after(resp: httpx.Response, defaut: float) -> float:
    valeur = resp.headers.get("Retry-After")
    try:
        return float(valeur) if valeur else defaut
    except ValueError:
        return defaut


# ── Essai d'un fournisseur ───────────────────────────────────────────────────
def _essayer_provider(cfg: ProviderConfig, messages: list, temperature: float,
                      max_tokens: int, json_mode: bool, erreurs: list) -> Optional[LLMResponse]:
    state = _STATES[cfg.name]

    for tentative in range(1, MAX_TENTATIVES_PAR_PROVIDER + 1):
        state.wait()
        t0 = time.monotonic()

        try:
            resp = _envoyer(cfg, messages, temperature, max_tokens, json_mode)
        except (httpx.ConnectError, httpx.ConnectTimeout):
            state.mettre_en_pause(30)
            erreurs.append(f"{cfg.name}: injoignable")
            logger.warning("LLM %s injoignable — ignoré pendant 30 s", cfg.name)
            return None
        except httpx.TransportError as e:
            erreurs.append(f"{cfg.name}: {type(e).__name__}")
            logger.warning("LLM %s : %s (tentative %d)", cfg.name, type(e).__name__, tentative)
            if tentative < MAX_TENTATIVES_PAR_PROVIDER:
                time.sleep(2 * tentative)
                continue
            return None

        if resp.status_code == 429:
            delai = _retry_after(resp, 20.0)
            state.mettre_en_pause(delai)
            erreurs.append(f"{cfg.name}: 429")
            logger.warning("LLM %s rate limit (429) — pause %.0f s, bascule vers le suivant", cfg.name, delai)
            return None

        if resp.status_code >= 500:
            erreurs.append(f"{cfg.name}: HTTP {resp.status_code}")
            logger.warning("LLM %s erreur serveur %d (tentative %d)", cfg.name, resp.status_code, tentative)
            if tentative < MAX_TENTATIVES_PAR_PROVIDER:
                time.sleep(2 * tentative)
                continue
            return None

        if resp.status_code >= 400:
            erreurs.append(f"{cfg.name}: HTTP {resp.status_code}")
            logger.error("LLM %s erreur %d : %s", cfg.name, resp.status_code, resp.text[:300])
            return None

        try:
            contenu, p_tok, c_tok = _extraire_contenu(cfg, resp.json())
        except Exception as e:
            erreurs.append(f"{cfg.name}: réponse illisible")
            logger.error("LLM %s réponse illisible : %s", cfg.name, e)
            return None

        duree_ms = round((time.monotonic() - t0) * 1000, 1)
        logger.info("LLM %s (%s) OK — %d+%d tokens, %.0f ms",
                    cfg.name, cfg.model, p_tok, c_tok, duree_ms)
        return LLMResponse(contenu, cfg.name, cfg.model, duree_ms, p_tok, c_tok)

    return None


def _attente_minimale(route: list[str]) -> Optional[float]:
    attentes = [
        _STATES[n].attente_restante()
        for n in route
        if n in _PROVIDERS and _PROVIDERS[n].configure and not _STATES[n].disponible()
    ]
    return min(attentes) if attentes else None


# ── Point d'entrée unique ────────────────────────────────────────────────────
def chat_completion(task: str, messages: list, temperature: float = 0.1,
                    max_tokens: int = 1000, json_mode: bool = False,
                    route: Optional[list[str]] = None) -> LLMResponse:
    """
    Appelle le premier fournisseur disponible de la route de `task`.
    `route` (optionnel) remplace la route configurée, ex : ["mistral"].
    Lève LLMUnavailableError si aucun ne répond.
    """
    route   = route or _route(task)
    erreurs: list[str] = []

    for passe in (1, 2):
        for nom in route:
            cfg = _PROVIDERS.get(nom)
            if cfg is None:
                erreurs.append(f"{nom}: inconnu")
                continue
            if not cfg.configure:
                continue  # pas de clé API : ignoré silencieusement
            if not _STATES[nom].disponible():
                continue  # en pause après un 429 ou injoignable

            reponse = _essayer_provider(cfg, messages, temperature, max_tokens, json_mode, erreurs)
            if reponse is not None:
                return reponse

        # Tous les fournisseurs sont en pause : on attend le plus court si c'est raisonnable
        attente = _attente_minimale(route)
        if passe == 1 and attente is not None and attente <= LLM_MAX_ATTENTE:
            logger.warning("Tous les LLM de la route '%s' en pause — attente %.1f s", task, attente)
            time.sleep(attente + 0.1)
            continue
        break

    detail = " | ".join(erreurs) if erreurs else "fournisseurs en pause ou non configurés"
    raise LLMUnavailableError(f"Aucun LLM disponible pour '{task}' — {detail}")


def etat() -> dict:
    """Pour un endpoint /health : quels fournisseurs sont configurés et en pause."""
    return {
        "routes": {t: _route(t) for t in _DEFAULT_ROUTES},
        "providers": {
            nom: {
                "model":         cfg.model,
                "configure":     cfg.configure,
                "en_pause_s":    round(_STATES[nom].attente_restante(), 1),
            }
            for nom, cfg in _PROVIDERS.items()
        },
    }