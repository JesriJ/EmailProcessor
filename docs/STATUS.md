# Implementation status

Last updated: 2026-10-05

## Completed

| Area | Status |
|------|--------|
| Postgres cache + Redis thin jobs | Done |
| Homogeneous workers, retries, DLQ, pending recovery | Done |
| FastAPI AI service (mock / Ollama / OpenAI-compatible) | Done |
| Synthetic producer + fixed 10k dataset | Done |
| Gmail ingest (live client + mock client) + OAuth helper | Done |
| Category freeze (`categories.yml` → `categories.lock`) | Done |
| Ops dashboard + email browse/filter + reply links | Done |
| Evaluation / benchmark / failure scripts | Done |

## Measured checks (local)

| Check | Result |
|-------|--------|
| Synthetic gate (100) | Cached and processed with empty DLQ (mock LLM) |
| Mock Gmail ingest (5) | Enqueued and processed end-to-end |
| Labeled evaluation (14 samples, Qwen3 8B) | Accuracy 0.8571, macro-F1 0.8571 |
| Worker crash recovery (200, Qwen) | processed=200, dlq=0, missing=0 |
| AI outage recovery (200, Qwen) | processed=125, dlq=75, missing=0 |
| Full 10k × worker scaling (Qwen) | Harness provided; run locally when GPU time allows |

## Intentionally unfinished / out of scope

- Hosted multi-user authentication on the admin UI
- Gmail send / draft creation
- Incremental Gmail History API sync (current ingest is lookback query + pagination)

Record new measurements under `evaluation/results/` and `benchmark/results/` (gitignored by default). Commit only JSON you intend to publish as evidence.
