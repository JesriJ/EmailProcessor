#!/usr/bin/env python3
"""Compute speedup and parallel efficiency from benchmark result JSON files."""

from __future__ import annotations

import json
from pathlib import Path

RESULTS = Path(__file__).resolve().parents[1] / "results"


def main() -> None:
    files = sorted(RESULTS.glob("bench-w*-c*-t*.json"))
    rows = [json.loads(p.read_text(encoding="utf-8")) for p in files]
    if not rows:
        print("No benchmark results found")
        return
    by_workers: dict[int, list[float]] = {}
    for row in rows:
        by_workers.setdefault(int(row["workerCount"]), []).append(float(row["emailsPerMinute"]))
    baseline = None
    summary = []
    for workers in sorted(by_workers):
        thr = sum(by_workers[workers]) / len(by_workers[workers])
        if baseline is None:
            baseline = thr
        speedup = thr / baseline if baseline else 0
        efficiency = speedup / workers if workers else 0
        summary.append(
            {
                "workers": workers,
                "avgEmailsPerMinute": round(thr, 2),
                "speedup": round(speedup, 3),
                "parallelEfficiency": round(efficiency, 3),
                "trials": len(by_workers[workers]),
            }
        )
    out = RESULTS / "scaling-summary.json"
    out.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
