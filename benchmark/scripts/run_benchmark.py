#!/usr/bin/env python3
"""Run scaling benchmark measurements against a live stack (synthetic path)."""

from __future__ import annotations

import argparse
import json
import statistics
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RESULTS = ROOT / "benchmark" / "results"


def http_json(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=30) as resp:
        return json.loads(resp.read().decode("utf-8"))


def wait_processed(worker_url: str, target: int, timeout_s: int = 3600) -> dict:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        stats = http_json(worker_url.rstrip("/") + "/admin/stats")
        if int(stats.get("processed", 0)) >= target:
            return stats
        time.sleep(2)
    raise TimeoutError(f"Timed out waiting for {target} processed emails")


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    values = sorted(values)
    k = (len(values) - 1) * p
    f = int(k)
    c = min(f + 1, len(values) - 1)
    if f == c:
        return values[f]
    return values[f] + (values[c] - values[f]) * (k - f)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--workers", type=int, required=True)
    parser.add_argument("--concurrency", type=int, default=4)
    parser.add_argument("--emails", type=int, default=10000)
    parser.add_argument("--worker-url", default="http://localhost:8080")
    parser.add_argument("--trial", type=int, default=1)
    args = parser.parse_args()

    started = time.time()
    start_stats = http_json(args.worker_url.rstrip("/") + "/admin/stats")
    start_processed = int(start_stats.get("processed", 0))
    target = start_processed + args.emails
    # Caller is responsible for reset/load/start workers before invoking.
    end_stats = wait_processed(args.worker_url, target)
    elapsed = max(time.time() - started, 0.001)
    processed = int(end_stats["processed"]) - start_processed
    emails_per_min = processed / elapsed * 60.0

    # Latency sample from prometheus is optional; store placeholders filled by SQL export if available.
    result = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "workerCount": args.workers,
        "workerConcurrency": args.concurrency,
        "emailsProcessed": processed,
        "elapsedSeconds": round(elapsed, 3),
        "emailsPerMinute": round(emails_per_min, 2),
        "averageLatencyMs": None,
        "p95LatencyMs": None,
        "retryCount": None,
        "dlqCount": int(end_stats.get("dlqStreamLength", 0)),
        "dataset": "benchmark/datasets/emails-10k-v1.jsonl",
        "notes": "Populate latency/retry fields from metrics export when available.",
    }
    RESULTS.mkdir(parents=True, exist_ok=True)
    out = RESULTS / f"bench-w{args.workers}-c{args.concurrency}-t{args.trial}.json"
    out.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
