# Security

## Secrets

Never commit:

- `.env`
- `config/gmail-client-secrets.json`
- OAuth refresh tokens, API keys, private keys

Use `.env.example` and `config/gmail-client-secrets.example.json` as templates. `.gitignore` excludes secret paths.

If a client secret or refresh token was ever shared or committed:

1. Revoke the OAuth client or secret in Google Cloud Console
2. Revoke app access at https://myaccount.google.com/permissions
3. Rotate `OPENAI_API_KEY` / database passwords as applicable
4. Generate new credentials and update local `.env` only

## Local network exposure

Compose publishes Postgres, Redis, AI, and the admin gateway on **127.0.0.1** only. The admin UI and `/admin/*` API are **not authenticated**.

Do not:

- Remap ports to `0.0.0.0` on an untrusted network
- Deploy this stack publicly without adding authentication, TLS, and strong DB credentials

Default Compose database password (`email`) is for local development. Change `POSTGRES_PASSWORD` before any shared environment.

## Gmail scope

Ingest requests `gmail.readonly` only. The application does not send email or modify mailbox state. Reply actions open Gmail or a `mailto:` link in the operator’s browser/mail client.

## Data isolation

Repository clones do not include Docker volumes. Each operator’s mailbox content stays on their machine unless they export it themselves.

## Dependency and supply chain

Pin service dependencies via Maven/`requirements.txt` and Compose image tags. Review updates before upgrading in environments that hold real mail.
