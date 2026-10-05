#!/usr/bin/env python3
"""Produce a reconciliation report from worker /admin/stats."""

from __future__ import annotations

import argparse
import json
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

RESULTS = Path(__file__).resolve().parents[1] / "results"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--worker-url", default="http://localhost:8080")
    parser.add_argument("--submitted", type=int, required=True)
    parser.add_argument("--scenario", default="manual")
    args = parser.parse_args()

    with urllib.request.urlopen(args.worker_url.rstrip("/") + "/admin/stats", timeout=30) as resp:
        stats = json.loads(resp.read().decode("utf-8"))

    processed = int(stats.get("processed", 0))
    dlq = int(stats.get("dlqStreamLength", 0))
    missing = args.submitted - processed - dlq
    report = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "scenario": args.scenario,
        "submitted": args.submitted,
        "processed": processed,
        "dlq": dlq,
        "missing": missing,
        "duplicates": 0,
        "cached": int(stats.get("cached", 0)),
        "notes": "duplicates=0 assumes email_id PK; verify with SQL if needed.",
    }
    RESULTS.mkdir(parents=True, exist_ok=True)
    out = RESULTS / f"reconcile-{args.scenario}.json"
    out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    if missing != 0:
        raise SystemExit(f"Unexplained missing jobs: {missing}")


if __name__ == "__main__":
    main()
