# EmailProcessor

Distributed email classification pipeline: ingest mail (Gmail API or synthetic JSONL), persist message bodies in PostgreSQL, enqueue thin jobs on Redis Streams, process with homogeneous Spring Boot workers, and classify with a FastAPI model service (Ollama, OpenAI-compatible APIs, or a deterministic mock).

## Features

- Dual ingest: **Gmail API** (OAuth refresh token) or **synthetic** JSONL for tests
- Durable `email_cache` + thin Redis jobs `{ emailId, cacheId }`
- At-least-once delivery, DB idempotency, retries, DLQ, pending recovery (`XCLAIM`)
- Locked classification taxonomy (`config/categories.lock`)
- Local ops dashboard (category browse, priority filter, Gmail / mailto reply links)
- Benchmark, evaluation, and failure-injection harnesses

## Architecture

```text
Synthetic JSONL | Gmail API
        │
        ▼
   normalize / cache
        │
        ▼
 PostgreSQL email_cache
        │
        ▼
 Redis Stream emails:incoming   ({emailId, cacheId})
        │
        ▼
 Worker  →  AI service  →  processed_emails  →  XACK
        │
        ▼
 Dashboard  http://127.0.0.1:8088/
```

| Component | Role |
|-----------|------|
| `ingest-service` | One-shot Gmail (or mock Gmail) sync → cache + enqueue |
| `producer` | Synthetic JSONL → cache + enqueue |
| `worker-service` | Stream consumer, AI call, persistence, admin API + UI |
| `ai-service` | Structured classification (Ollama / OpenAI / mock) |
| `gateway` | Local reverse proxy to worker UI/API on port 8088 |

## Requirements

- Docker Desktop with Compose
- Python 3.12+ (OAuth helper, evaluation scripts)
- Optional: [Ollama](https://ollama.com) for local models (default `qwen3:8b`)

## Quick start

```powershell
git clone <your-repo-url> EmailProcessor
cd EmailProcessor
copy .env.example .env
python scripts/freeze_categories.py
powershell -File scripts/start_stack.ps1 -Workers 1 -Llm mock
```

| URL | Purpose |
|-----|---------|
| http://127.0.0.1:8088/ | Dashboard |
| http://127.0.0.1:8088/admin/stats | Pipeline stats JSON |
| http://127.0.0.1:8000/health | AI service health |

Synthetic smoke:

```powershell
docker compose --profile producer run --rm producer
```

## Configuration

Copy `.env.example` → `.env`. Important variables:

| Variable | Purpose |
|----------|---------|
| `LLM_PROVIDER` | `ollama` \| `openai` \| `mock` |
| `OLLAMA_*` / `OPENAI_*` | Model endpoints and credentials |
| `MAIL_MODE` | `synthetic` \| `gmail` |
| `USE_MOCK_GMAIL` | `true` for fixture Gmail; `false` for live API |
| `GOOGLE_*` | OAuth client id/secret + refresh token |
| `GMAIL_SYNC_*` / `GMAIL_QUERY` | Sync window and Gmail search operators |
| `WORKER_CONCURRENCY` | In-flight jobs per worker (keep near local LLM parallel slots) |
| `POSTGRES_*` / `REDIS_*` | Datastores (change defaults before any shared deploy) |

Published ports bind to **localhost** only. Do not expose the admin UI or databases to the public internet without authentication and hardened credentials.

## Choosing categories

1. Edit `config/categories.yml`
2. Run `python scripts/freeze_categories.py` (writes `config/categories.lock`)
3. Recreate the AI service: `docker compose up -d --force-recreate ai-service`

The model may only return labels present in the lock. Changing the taxonomy after production data exists usually requires reprocessing.

## Gmail setup

1. Google Cloud Console → enable **Gmail API**
2. OAuth consent screen → add your account under **Test users** (required while the app is in Testing)
3. Create OAuth client → type **Desktop app** → download JSON to `config/gmail-client-secrets.json` (gitignored)
4. Obtain a refresh token:

```powershell
pip install google-auth-oauthlib google-auth-httplib2
python scripts/gmail_oauth.py
```

5. Paste printed `GOOGLE_*` values into `.env`, set `MAIL_MODE=gmail` and `USE_MOCK_GMAIL=false`
6. Start stack and ingest:

```powershell
powershell -File scripts/start_stack.ps1 -Workers 1 -Llm ollama
powershell -File scripts/run_gmail_ingest.ps1
```

**OAuth `403: access_denied`:** your Google account is not on the consent screen test-user list. Add it and retry.

Scope used: `gmail.readonly`. The app does not send mail; the dashboard opens the Gmail thread (or a `mailto:` link) so a person can reply.

## Resetting data

Clear cached mail, results, and queues (keeps schema):

```powershell
powershell -File scripts/reset_data.ps1
```

Full database volume wipe:

```powershell
docker compose down
docker volume rm emailprocessor_pgdata
powershell -File scripts/start_stack.ps1 -Workers 1 -Llm mock
```

Cloning this repository never includes another user’s mailbox or Postgres volume—only code and sample/synthetic assets.

## Admin API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/admin/stats` | Counts and breakdowns |
| GET | `/admin/emails` | Paginated list (`classification`, `priority`, `q`, `page`, `size`) |
| GET | `/admin/emails/{emailId}` | Detail + body + reply links |
| GET | `/admin/recent` | Latest processed rows |
| GET | `/admin/dlq` | Dead-letter peek |
| POST | `/admin/dlq/{emailId}/replay` | Re-enqueue from DLQ |
| POST | `/admin/reprocess?fromCacheId=&toCacheId=` | Re-enqueue cache range |

## Benchmarks and evaluation

See [docs/SPECIFICATION.md](docs/SPECIFICATION.md) and [benchmark/README.md](benchmark/README.md).

```powershell
python evaluation/scripts/run_evaluation.py --out-name evaluation-local.json
powershell -File benchmark/scripts/run_10k_qwen.ps1 -WorkerCounts "1,2,4" -Emails 10000 -Concurrency 2
powershell -File failure-tests/scripts/run_reliability_qwen.ps1 -Emails 200
```

## Documentation

| Doc | Contents |
|-----|----------|
| [docs/SPECIFICATION.md](docs/SPECIFICATION.md) | System specification, data model, delivery guarantees |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | Day-2 ops: reset, scale, OAuth issues, LLM tuning |
| [docs/STATUS.md](docs/STATUS.md) | Implementation status and measured checks |
| [SECURITY.md](SECURITY.md) | Secrets, localhost bindings, threat notes |

## Development tests

```powershell
cd worker-service; mvn test
cd ..\ai-service; python -m pytest tests -q
```

## License

MIT — see [LICENSE](LICENSE).
