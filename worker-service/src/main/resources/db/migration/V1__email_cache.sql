CREATE TABLE email_cache (
    cache_id              BIGSERIAL PRIMARY KEY,
    email_id              TEXT NOT NULL,
    source                TEXT NOT NULL,
    provider_message_id   TEXT NULL,
    subject               TEXT NOT NULL,
    body_text             TEXT NOT NULL,
    sender                TEXT NOT NULL,
    received_at           TIMESTAMPTZ NOT NULL,
    labels_json           TEXT NULL,
    ingested_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    enqueued_at           TIMESTAMPTZ NULL,
    CONSTRAINT uq_email_cache_email_id UNIQUE (email_id),
    CONSTRAINT uq_email_cache_provider_message_id UNIQUE (provider_message_id)
);

CREATE INDEX idx_email_cache_enqueued_at ON email_cache (enqueued_at);
CREATE INDEX idx_email_cache_source ON email_cache (source);
