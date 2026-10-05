ALTER TABLE email_cache
    ADD COLUMN IF NOT EXISTS provider_thread_id TEXT NULL;

CREATE INDEX IF NOT EXISTS idx_email_cache_provider_thread_id
    ON email_cache (provider_thread_id);
