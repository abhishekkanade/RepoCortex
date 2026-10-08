# RepoCortex

Paste any public GitHub repo URL, no login needed. RepoCortex indexes the code and answers questions about it, with citations that link to the exact lines on GitHub.

- `backend/` - Spring Boot (Java 21), Spring AI, Postgres + pgvector
- AI: chat via Groq, embeddings via Google Gemini (both OpenAI-compatible, configurable in `backend/src/main/resources/application.yml`)
- `frontend/` - Next.js, TypeScript, Tailwind, shadcn

## Run locally

Requirements: Java 21+, Node 20+, Docker.

Set the environment variables:

```bash
export GROQ_API_KEY=gsk_...       # chat
export GEMINI_API_KEY=...         # embeddings
export GITHUB_TOKEN=ghp_...
```

To use other OpenAI-compatible providers, override `CHAT_BASE_URL`, `CHAT_MODEL`, `EMBEDDING_BASE_URL`, `EMBEDDING_MODEL` (or edit `application.yml`). On a paid embedding plan set `EMBEDDING_TOKENS_PER_MINUTE=0` to turn off client-side pacing.

Backend (Postgres starts automatically via Docker Compose on port 5433):

```bash
cd backend
./mvnw spring-boot:run      # Windows: mvnw.cmd spring-boot:run
```

The API runs on http://localhost:8080.

Frontend:

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:3000.
