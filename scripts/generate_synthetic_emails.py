#!/usr/bin/env python3
"""Generate fixed synthetic email datasets for bench/test."""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

TEMPLATES = [
    ("billing", "Duplicate charge", "I was charged twice for my order. Please refund the extra charge."),
    ("technical", "App keeps crashing", "The mobile app crashes when I open settings. Please help."),
    ("shipping", "Where is my package", "My tracking number has not updated for five days."),
    ("account", "Cannot reset password", "The password reset email never arrives for my account."),
    ("product", "Feature question", "Does the premium plan include API access and SSO?"),
    ("complaint", "Poor support experience", "I have waited three days with no reply to my ticket."),
    ("other", "Office hours", "What are your customer support hours this week?"),
]


def generate(count: int, prefix: str) -> list[dict]:
    base = datetime(2026, 10, 1, tzinfo=timezone.utc)
    rows = []
    for i in range(1, count + 1):
        category, subject, body = TEMPLATES[(i - 1) % len(TEMPLATES)]
        rows.append(
            {
                "emailId": f"{prefix}-{i:06d}",
                "subject": f"{subject} #{i}",
                "body": f"{body} (category_hint={category})",
                "sender": f"customer{i}@example.com",
                "receivedAt": (base + timedelta(seconds=i)).isoformat().replace("+00:00", "Z"),
            }
        )
    return rows


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as fh:
        for row in rows:
            fh.write(json.dumps(row) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=100)
    parser.add_argument("--prefix", default="synth")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    rows = generate(args.count, args.prefix)
    write_jsonl(args.out, rows)
    print(f"Wrote {len(rows)} emails to {args.out}")


if __name__ == "__main__":
    main()
