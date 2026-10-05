#!/usr/bin/env python3
"""Freeze config/categories.yml into config/categories.lock."""

from __future__ import annotations

import hashlib
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "config" / "categories.yml"
DST = ROOT / "config" / "categories.lock"


def main() -> int:
    if not SRC.exists():
        print(f"Missing {SRC}", file=sys.stderr)
        return 1
    data = yaml.safe_load(SRC.read_text(encoding="utf-8"))
    categories = data.get("categories") or []
    if not categories:
        print("categories list is empty", file=sys.stderr)
        return 1
    cleaned = []
    for item in categories:
        if not isinstance(item, str) or not re.fullmatch(r"[a-z][a-z0-9_]*", item):
            print(f"invalid category: {item}", file=sys.stderr)
            return 1
        cleaned.append(item)
    if len(cleaned) != len(set(cleaned)):
        print("duplicate categories", file=sys.stderr)
        return 1
    payload = {
        "domain": data.get("domain", "customer_support"),
        "promptVersion": data.get("promptVersion", "v1"),
        "categories": cleaned,
        "frozenAt": datetime.now(timezone.utc).isoformat(),
        "sourceHash": hashlib.sha256(SRC.read_bytes()).hexdigest(),
    }
    DST.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {DST} with {len(cleaned)} categories")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
