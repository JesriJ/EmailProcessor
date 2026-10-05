#!/usr/bin/env python3
"""Evaluate classification quality using the AI service (mock or real)."""

from __future__ import annotations

import argparse
import json
import os
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parents[2]
DATASET = ROOT / "evaluation" / "datasets" / "labeled-v1.jsonl"
RESULTS = ROOT / "evaluation" / "results"


def metrics(y_true: list[str], y_pred: list[str]) -> dict:
    labels = sorted(set(y_true) | set(y_pred))
    correct = sum(1 for a, b in zip(y_true, y_pred) if a == b)
    accuracy = correct / len(y_true) if y_true else 0.0
    precision_sum = recall_sum = f1_sum = 0.0
    matrix = {t: Counter() for t in labels}
    for t, p in zip(y_true, y_pred):
        matrix[t][p] += 1
    for label in labels:
        tp = matrix[label][label]
        fp = sum(matrix[o][label] for o in labels if o != label)
        fn = sum(c for l, c in matrix[label].items() if l != label)
        prec = tp / (tp + fp) if (tp + fp) else 0.0
        rec = tp / (tp + fn) if (tp + fn) else 0.0
        f1 = 2 * prec * rec / (prec + rec) if (prec + rec) else 0.0
        precision_sum += prec
        recall_sum += rec
        f1_sum += f1
    n = len(labels) or 1
    return {
        "accuracy": round(accuracy, 4),
        "macroPrecision": round(precision_sum / n, 4),
        "macroRecall": round(recall_sum / n, 4),
        "macroF1": round(f1_sum / n, 4),
        "confusionMatrix": {k: dict(v) for k, v in matrix.items()},
    }


def predict_with_retry(client: httpx.Client, url: str, row: dict, attempts: int = 5) -> dict:
    last_error = None
    for attempt in range(1, attempts + 1):
        try:
            resp = client.post(
                url,
                json={
                    "emailId": row["emailId"],
                    "subject": row["subject"],
                    "body": row["body"],
                    "attempt": attempt,
                },
            )
            if resp.status_code < 400:
                return resp.json()
            last_error = f"{resp.status_code} {resp.text}"
        except (httpx.TimeoutException, httpx.TransportError) as exc:
            last_error = f"{type(exc).__name__}: {exc}"
        time.sleep(2.0 * attempt)
    raise RuntimeError(f"Failed {row['emailId']}: {last_error}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--ai-url", default="http://localhost:8000")
    parser.add_argument("--dataset", type=Path, default=DATASET)
    parser.add_argument("--out-name", default="evaluation-latest.json")
    parser.add_argument("--timeout", type=float, default=300.0)
    args = parser.parse_args()

    rows = [
        json.loads(line)
        for line in args.dataset.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    y_true, y_pred = [], []
    total_in = total_out = 0
    details = []
    with httpx.Client(timeout=args.timeout) as client:
        url = args.ai_url.rstrip("/") + "/process-email"
        for row in rows:
            payload = predict_with_retry(client, url, row)
            y_true.append(row["label"])
            y_pred.append(payload["classification"])
            total_in += int(payload.get("inputTokens") or 0)
            total_out += int(payload.get("outputTokens") or 0)
            details.append(
                {
                    "emailId": row["emailId"],
                    "label": row["label"],
                    "prediction": payload["classification"],
                    "correct": row["label"] == payload["classification"],
                    "priority": payload.get("priority"),
                    "model": payload.get("model"),
                }
            )
            print(
                f"{row['emailId']}: label={row['label']} pred={payload['classification']} "
                f"{'OK' if row['label'] == payload['classification'] else 'MISS'}"
            )

    report = metrics(y_true, y_pred)
    input_price = float(os.getenv("INPUT_PRICE_PER_1M", "0"))
    output_price = float(os.getenv("OUTPUT_PRICE_PER_1M", "0"))
    cost = (total_in / 1_000_000) * input_price + (total_out / 1_000_000) * output_price
    report.update(
        {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "dataset": str(args.dataset),
            "samples": len(rows),
            "model": details[0]["model"] if details else None,
            "inputTokens": total_in,
            "outputTokens": total_out,
            "tokensPer1000Emails": round((total_in + total_out) / max(len(rows), 1) * 1000, 2),
            "estimatedCostUsd": round(cost, 6),
            "pricing": {
                "inputPer1M": input_price,
                "outputPer1M": output_price,
            },
            "details": details,
        }
    )
    RESULTS.mkdir(parents=True, exist_ok=True)
    out = RESULTS / args.out_name
    out.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    (RESULTS / "evaluation-latest.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: v for k, v in report.items() if k != "details"}, indent=2))


if __name__ == "__main__":
    main()
