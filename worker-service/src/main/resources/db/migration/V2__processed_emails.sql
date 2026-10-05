CREATE TABLE processed_emails (
    email_id                 TEXT PRIMARY KEY,
    cache_id                 BIGINT NOT NULL REFERENCES email_cache (cache_id),
    classification           TEXT NOT NULL,
    summary                  TEXT NOT NULL,
    priority                 TEXT NOT NULL,
    action_required          BOOLEAN NOT NULL,
    suggested_action         TEXT NOT NULL,
    model                    TEXT NOT NULL,
    prompt_version           TEXT NOT NULL,
    attempt_count            INTEGER NOT NULL DEFAULT 1,
    processing_duration_ms   BIGINT NOT NULL,
    input_tokens             INTEGER NULL,
    output_tokens            INTEGER NULL,
    processed_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_processed_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'URGENT'))
);

CREATE INDEX idx_processed_emails_processed_at ON processed_emails (processed_at);
CREATE INDEX idx_processed_emails_classification ON processed_emails (classification);
