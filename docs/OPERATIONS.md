# Operations guide

## Start / stop

```powershell
powershell -File scripts/start_stack.ps1 -Workers 1 -Llm mock    # fast local
powershell -File scripts/start_stack.ps1 -Workers 2 -Llm ollama  # local model
docker compose down                                              # stop containers (keeps volumes)
```

LLM modes:

| `-Llm` | Effect |
|--------|--------|
| `mock` | Deterministic classifier; best for pipeline checks |
| `ollama` | Local model via host Ollama (`OLLAMA_MODEL`) |
| `openai` | OpenAI-compatible API (`OPENAI_API_KEY`, `OPENAI_MODEL`) |

## Ingest

```powershell
# Live Gmail (requires .env credentials)
powershell -File scripts/run_gmail_ingest.ps1

# Fixture Gmail (no Google account)
powershell -File scripts/run_gmail_ingest.ps1 -Mock

# Synthetic JSONL
docker compose --profile producer run --rm producer
```

## Reset data

```powershell
powershell -File scripts/reset_data.ps1
```

This truncates `email_cache` and `processed_emails`, and deletes Redis streams `emails:incoming` and `emails:dlq`.

Nuclear reset (destroys Postgres volume):

```powershell
docker compose down
docker volume rm emailprocessor_pgdata
powershell -File scripts/start_stack.ps1 -Workers 1 -Llm mock
```

## Categories

```powershell
# edit config/categories.yml
python scripts/freeze_categories.py
docker compose up -d --force-recreate ai-service
```

## Scaling workers

```powershell
docker compose up -d --scale worker=4 worker
```

Do not publish a fixed host port on every worker replica (Compose collision). Use the `gateway` service for UI/API access.

For local LLMs, set `WORKER_CONCURRENCY` close to `OLLAMA_NUM_PARALLEL`. Extra workers beyond GPU parallel slots mostly queue.

## Gmail OAuth troubleshooting

| Symptom | Action |
|---------|--------|
| `403 access_denied` / “not completed verification” | Add the Google account under OAuth consent screen → **Test users** |
| No refresh token returned | Revoke app access at https://myaccount.google.com/permissions and re-run `gmail_oauth.py` |
| Empty ingest | Widen `GMAIL_SYNC_LOOKBACK_DAYS` or relax `GMAIL_QUERY` |
| `docker` not recognized | Start Docker Desktop; open a new terminal; scripts also prepend the Desktop CLI path |

## Dashboard reply links

- **Open in Gmail** — requires live ingest with a real `provider_thread_id`
- **mailto** — always available when a sender address is present (synthetic/mock included)

## Health checks

```powershell
curl.exe http://127.0.0.1:8000/health
curl.exe http://127.0.0.1:8088/admin/stats
docker compose ps
```
