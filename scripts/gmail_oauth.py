#!/usr/bin/env python3
"""One-time Gmail OAuth helper — prints a refresh token for .env."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

SCOPES = ["https://www.googleapis.com/auth/gmail.readonly"]
DEFAULT_REDIRECT = "http://localhost:8085/"


def main() -> int:
    parser = argparse.ArgumentParser(description="Obtain a Gmail refresh token for EmailProcessor")
    parser.add_argument(
        "--client-secrets",
        type=Path,
        default=Path("config/gmail-client-secrets.json"),
        help="OAuth desktop client secrets JSON from Google Cloud Console",
    )
    parser.add_argument(
        "--client-id",
        help="Optional client id (skips secrets file if secret also provided)",
    )
    parser.add_argument("--client-secret", help="Optional client secret")
    args = parser.parse_args()

    try:
        from google_auth_oauthlib.flow import InstalledAppFlow
    except ImportError:
        print(
            "Missing dependency. Install with:\n"
            "  pip install google-auth-oauthlib google-auth-httplib2\n",
            file=sys.stderr,
        )
        return 1

    if args.client_id and args.client_secret:
        client_config = {
            "installed": {
                "client_id": args.client_id,
                "client_secret": args.client_secret,
                "auth_uri": "https://accounts.google.com/o/oauth2/auth",
                "token_uri": "https://oauth2.googleapis.com/token",
                "redirect_uris": [DEFAULT_REDIRECT],
            }
        }
        flow = InstalledAppFlow.from_client_config(client_config, SCOPES)
    else:
        if not args.client_secrets.exists():
            print(
                f"Client secrets not found: {args.client_secrets}\n\n"
                "1. Google Cloud Console → APIs & Services → enable Gmail API\n"
                "2. Create OAuth client ID type Desktop app\n"
                "3. Download JSON to config/gmail-client-secrets.json\n"
                "   (or pass --client-id / --client-secret)\n",
                file=sys.stderr,
            )
            return 1
        flow = InstalledAppFlow.from_client_secrets_file(str(args.client_secrets), SCOPES)

    creds = flow.run_local_server(port=8085, prompt="consent", access_type="offline")
    if not creds.refresh_token:
        print(
            "No refresh_token returned. Revoke prior access at "
            "https://myaccount.google.com/permissions and retry with prompt=consent.",
            file=sys.stderr,
        )
        return 1

    client_id = creds.client_id
    client_secret = creds.client_secret
    print("\n=== Add these to your .env ===\n")
    print("MAIL_MODE=gmail")
    print("USE_MOCK_GMAIL=false")
    print(f"GOOGLE_CLIENT_ID={client_id}")
    print(f"GOOGLE_CLIENT_SECRET={client_secret}")
    print(f"GOOGLE_REFRESH_TOKEN={creds.refresh_token}")
    print("\n=== JSON copy ===")
    print(
        json.dumps(
            {
                "GOOGLE_CLIENT_ID": client_id,
                "GOOGLE_CLIENT_SECRET": client_secret,
                "GOOGLE_REFRESH_TOKEN": creds.refresh_token,
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
