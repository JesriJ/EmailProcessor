# System specification

## Purpose

EmailProcessor classifies inbound customer-support style email into a frozen set of categories, assigns priority and a suggested action, and presents results for human review. It is designed as a small distributed system with explicit delivery and idempotency semantics—not as a hosted SaaS product.

## Design goals

1. **Durable bodies, thin queues** — message content lives in PostgreSQL; Redis carries pointers only.
2. **Homogeneous workers** — any worker replica can process any job.
3. **At-least-once processing** — duplicates are safe; lost acks recover via pending claim.
4. **Pluggable inference** — mock, local Ollama, or OpenAI-compatible HTTP APIs.
5. **Reproducible evaluation** — category taxonomy is locked before measured runs.

## Components

| Service | Language | Lifecycle |
|---------|----------|-----------|
| `producer` | Java / Spring Boot | One-shot CLI (Compose profile `producer`) |
| `ingest-service` | Java / Spring Boot | One-shot CLI (Compose profile `ingest`) |
| `worker-service` | Java / Spring Boot | Long-running consumer + HTTP admin/UI |
| `ai-service` | Python / FastAPI | Long-running inference HTTP API |
| `postgres` | PostgreSQL 16 | Stateful volume |
| `redis` | Redis 7 Streams | Job + DLQ streams |
| `gateway` | nginx | Local reverse proxy to worker |

## Data model

### `email_cache`

Canonical stored message after ingest.

| Column | Notes |
|--------|-------|
| `cache_id` | Surrogate PK |
| `email_id` | Stable logical id (`gmail-{id}` or synthetic id) |
| `source` | `gmail` \| `synthetic` |
| `provider_message_id` | Gmail message id (unique when present) |
| `provider_thread_id` | Gmail thread id (for deep links) |
| `subject`, `body_text`, `sender`, `received_at` | Normalized fields |
| `labels_json` | Optional provider labels |
| `ingested_at`, `enqueued_at` | Lifecycle timestamps |

### `processed_emails`

One logical result per `email_id` (primary key enforces idempotency).

| Column | Notes |
|--------|-------|
| `classification` | Must be in locked category set |
| `priority` | `LOW` \| `MEDIUM` \| `HIGH` \| `URGENT` |
| `summary`, `suggested_action` | Model output |
| `action_required` | Boolean |
| `model`, `prompt_version` | Provenance |
| `attempt_count`, `processing_duration_ms`, token fields | Ops metrics |
| `processed_at` | Completion time |

### Redis

| Stream | Payload |
|--------|---------|
| `emails:incoming` | `emailId`, `cacheId` |
| `emails:dlq` | Failed job fields + failure metadata |

Consumer group: `email-workers`.

## Processing pipeline

1. Ingest upserts `email_cache` and `XADD`s a thin job if not previously enqueued.
2. Worker `XREADGROUP` → load cache row → call AI `/process-email`.
3. On success, insert `processed_emails` then `XACK`.
4. On retryable failure, backoff and retry up to `MAX_RETRIES`.
5. On permanent failure or exhausted retries, write DLQ and ack (or equivalent terminal path).
6. Idle pending entries are claimed via `XCLAIM` / recovery loop.

## AI contract

`POST /process-email`

Request: `emailId`, `subject`, `body`, `attempt`

Response (JSON): `classification`, `summary`, `priority`, `actionRequired`, `suggestedAction`, `model`, `promptVersion`, optional token counts.

Invalid or out-of-taxonomy classifications are rejected by the AI service (HTTP 422) and treated as processing failures by the worker.

## Category freeze

- Source of truth for authors: `config/categories.yml`
- Runtime lock: `config/categories.lock` produced by `scripts/freeze_categories.py`
- Evaluation and production runs should use the same lock file for a given campaign

## Security boundaries (product assumptions)

- Single-operator / local deployment
- Admin HTTP API and UI are **unauthenticated**
- Gmail access is read-only OAuth
- Secrets live in environment / gitignored files only

See [SECURITY.md](../SECURITY.md).

## Non-goals

- Multi-tenant SaaS auth
- Sending mail or mutating Gmail labels
- Guaranteed exactly-once side effects outside the `processed_emails` primary key
- Training or fine-tuning models inside this repository
