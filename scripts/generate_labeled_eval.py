#!/usr/bin/env python3
"""Generate a small labeled evaluation dataset aligned to locked categories."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "evaluation" / "datasets" / "labeled-v1.jsonl"

SAMPLES = [
    ("billing", "Duplicate charge on invoice", "I was charged twice for order 1001. Please refund."),
    ("billing", "Wrong tax amount", "My invoice includes incorrect sales tax."),
    ("technical", "Login loop bug", "I cannot log in; the page refreshes endlessly."),
    ("technical", "API 500 errors", "Our integration receives HTTP 500 from your API."),
    ("shipping", "Lost package", "Carrier marked delivered but I never received the box."),
    ("shipping", "Delayed shipment", "My order is a week late according to tracking."),
    ("account", "Close my account", "Please delete my account and export my data."),
    ("account", "Update email address", "I need to change the email on my account."),
    ("product", "Pricing question", "What is included in the business plan?"),
    ("product", "SSO support", "Do you support SAML SSO for enterprise?"),
    ("complaint", "Rude agent", "Your support agent was dismissive and unhelpful."),
    ("complaint", "Repeated failures", "This is the third outage this month and I am frustrated."),
    ("other", "Newsletter unsubscribe", "Please stop sending marketing emails."),
    ("other", "Office location", "Where is your headquarters located?"),
]


def main() -> None:
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", encoding="utf-8") as fh:
        for idx, (label, subject, body) in enumerate(SAMPLES, start=1):
            row = {
                "emailId": f"eval-{idx:04d}",
                "subject": subject,
                "body": body,
                "sender": f"eval{idx}@example.com",
                "receivedAt": "2026-10-01T12:00:00Z",
                "label": label,
            }
            fh.write(json.dumps(row) + "\n")
    print(f"Wrote {len(SAMPLES)} labeled emails to {OUT}")


if __name__ == "__main__":
    main()
