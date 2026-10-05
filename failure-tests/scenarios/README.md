# Failure scenarios

Automated and manual failure injections for Block 3/4 gates.

| Scenario | How to inject | Expected |
|----------|---------------|----------|
| AI timeout | Stop `ai-service` or set read timeout low | Retry then recover or DLQ after limit |
| AI 5xx | Point `AI_SERVICE_URL` at a stub returning 500 | Retry |
| DB outage | `docker compose stop postgres` mid-run | No XACK; recovery after DB returns |
| Crash before DB commit | `docker compose kill worker` during processing | Pending → XAUTOCLAIM → complete |
| Crash after DB before XACK | Kill worker after row exists in `processed_emails` but before ack | Duplicate delivery → one logical result |

Use `failure-tests/scripts/reconcile.py --submitted N --scenario <name>` after each run.
