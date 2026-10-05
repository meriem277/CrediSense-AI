# CrediSense AI

Plateforme d'analyse et de décision de crédit bancaire assistée par IA. Le système ingère les documents d'un dossier client (CIN, fiches de paie, relevés, contrats...), les traite via un pipeline OCR + NLP + LLM, puis produit une analyse structurée et une aide à la décision, exposée via un tableau de bord interne et un portail client.

## Architecture

Le projet est composé de trois applications qui communiquent entre elles :

```
frontend/   Angular 21 (SSR) — portail interne (agents/admin) + portail client
Backend/    Spring Boot (Java) — API métier, auth, persistance, orchestration
Python/     FastAPI — microservice IA : OCR, NLP, classification, RAG, chatbot
```

```
┌────────────┐      REST/JWT      ┌───────────────┐      HTTP       ┌────────────────┐
│  Frontend  │  ────────────────► │    Backend     │ ───────────────►│  Python (IA)   │
│  Angular   │ ◄──────────────────│  Spring Boot   │◄─────────────── │   FastAPI      │
└────────────┘                    │ (port 8081)    │                 │ (port 8002)    │
                                   └───────┬────────┘                 └───────┬────────┘
                                           │ JPA                              │
                                           ▼                                  ▼
                                     PostgreSQL                    OCR / Embeddings / Groq LLM
                                    (creditsense)
```

## Backend — Spring Boot (`Backend/backend`)

- **Stack** : Spring Boot, Spring Security + JWT, Spring Data JPA, PostgreSQL, Lombok, springdoc-openapi (Swagger).
- **Traitement documentaire** : PDFBox, Apache POI (Office), iText, Tess4J (OCR).
- **Port** : `8081` · **Base de données** : `creditsense` (PostgreSQL, `localhost:5432`).

### Modules principaux (`src/main/java/com/example/crediSense`)

| Package | Rôle |
|---|---|
| `controller` | Endpoints REST (auth, dossiers, fichiers, agents, décisions, chatbot, RAG...) |
| `controller/Client` | Endpoints dédiés au portail client externe (auth client, demande de crédit, mot de passe oublié) |
| `Service` / `Service/impl` | Logique métier |
| `entity` | Modèle de données JPA (`Client`, `Dossier`, `Fichier`, `Agent`, `AgentAnalysis`, `DecisionFinale`, `JsonExtraction`, `OcrResult`, `RagContext`, `ClientAggregation`, `RoleType`) |
| `repository` | Repositories Spring Data |
| `dto/request`, `dto/response` | Contrats d'API |
| `jwt` | Génération/validation des tokens JWT |
| `config` | Configuration Spring (sécurité, CORS...) |

### Entités clés

- **Dossier** : dossier de demande de crédit d'un client, regroupe les fichiers et le résultat final.
- **Fichier** : document uploadé, lié à son `OcrResult` et sa `JsonExtraction`.
- **AgentAnalysis / DecisionFinale** : sorties de l'analyse IA et décision de crédit consolidée.
- **RagContext** : contexte récupéré pour le chatbot (retrieval-augmented generation).

## Microservice IA — Python / FastAPI (`Python/`)

- **Port** : `8002` (voir `config.py`)
- **Entrée** : `main.py`

### Services (`Python/services`)

| Service | Rôle |
|---|---|
| `ocr_service.py` | Extraction de texte des documents (docTR / PyMuPDF) |
| `nlp_service.py` | Classification NLP et vérification des documents requis |
| `document_classifier_service.py` | Classification hybride (embeddings sentence-transformers + fallback LLM) |
| `llm_classifier_service.py` | Classification via LLM |
| `groq_service.py` | Extraction structurée et scoring via l'API Groq (modèle `llama-3.3-70b-versatile`) |
| `chatbot_service.py` | Chatbot RAG sur les documents d'un dossier (FAISS) |
| `agent_service.py` | Orchestration des agents d'analyse |

### Endpoints principaux (`main.py`)

- `GET /health`
- Classification de texte (`/classify`, hybride)
- Vérification des documents requis
- OCR (à partir d'un chemin PDF)
- Extraction Groq (texte nettoyé → JSON structuré)
- Chat (`/chat`) avec contexte dossier
- Indexation RAG (`/index`)
- Analyse agent (consommation, etc.)

### Stack

FastAPI, Uvicorn, PyTorch, Transformers, Sentence-Transformers, FAISS, python-doctr, PyMuPDF, httpx, Pydantic.

## Frontend — Angular (`frontend/`)

- **Stack** : Angular 21 (standalone components, SSR via Express), RxJS.
- **Structure** : composants **lazy-loaded** par route, séparés en deux portails :

### Portail interne (agents / admin)

| Composant | Rôle |
|---|---|
| `login`, `register`, `reset-password` | Authentification des agents/admin |
| `dashboard` | Tableau de bord agent (`authGuard` + `agentGuard`) |
| `admin-dashboard` | Tableau de bord admin (`authGuard` + `adminGuard`) |
| `upload-section` | Upload des documents d'un dossier |
| `pipeline-analyse` | Suivi du pipeline d'analyse IA |
| `credit-result` | Résultat / décision de crédit |
| `chat-assistant` | Assistant conversationnel (RAG) |
| `client` | Gestion des clients côté interne |
| `export-button` | Export des résultats |

### Portail client externe (`/client/...`)

| Composant | Rôle |
|---|---|
| `clientloginetregister` | Auth du client final |
| `client-portal/demande` | Dépôt d'une demande de crédit |
| `client-portal/confirmation` | Confirmation de la demande |
| `reset-password` | Réinitialisation de mot de passe |

Les accès sont protégés par des guards (`auth.guard.ts`) : `authGuard`, `agentGuard`, `adminGuard`.

## Flux fonctionnel (bout en bout)

1. Le client dépose une demande et ses documents via le **portail externe** (ou un agent via le portail interne).
2. Le **Backend** stocke le dossier/fichiers (`Dossier`, `Fichier`) et déclenche le traitement.
3. Le **service Python** effectue l'OCR, classe les documents, extrait les données structurées via Groq et calcule un score/une analyse par agent.
4. Les résultats (`OcrResult`, `JsonExtraction`, `AgentAnalysis`) sont persistés côté Backend et agrégés en une `DecisionFinale`.
5. L'agent consulte le résultat dans `credit-result`, peut interroger le **chatbot RAG** pour poser des questions sur le dossier.

## Lancement en local

### Base de données
PostgreSQL, base `creditsense` sur `localhost:5432`.

### Backend
```bash
cd Backend/backend
./mvnw spring-boot:run
```
Démarre sur `http://localhost:8081`. Swagger UI disponible via springdoc-openapi.

### Service IA
```bash
cd Python
pip install -r requirements.txt
python main.py
```
Démarre sur `http://localhost:8002`.

### Frontend
```bash
cd frontend
npm install
npm start
```
Démarre sur `http://localhost:4200` (par défaut Angular).

## ⚠️ Point d'attention sécurité

`Backend/backend/src/main/resources/application.properties` contient actuellement **en clair** :
- une clé API Groq (`groq.api.key`)
- le mot de passe de la base PostgreSQL
- des identifiants SMTP

Ces valeurs devraient être déplacées vers des variables d'environnement (ou un fichier `.env`/`application-local.properties` ignoré par git) et la clé Groq exposée devrait être révoquée/régénérée si ce fichier a déjà été poussé sur un dépôt partagé.
