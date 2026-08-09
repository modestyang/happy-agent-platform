-- V5: fields and indexes required to persist and inspect real Run/Trace records.
-- Intentionally plain PostgreSQL: no vector, extension, analytics, or dashboard tables.
-- The pgvector-dependent RAG tables live in V6, which is allowed to fail.

BEGIN;

-- 1. Observability enrichment on agent_runs ---------------------------------
ALTER TABLE agent_runs
    ADD COLUMN IF NOT EXISTS prompt_tokens INTEGER NOT NULL DEFAULT 0
        CHECK (prompt_tokens >= 0),
    ADD COLUMN IF NOT EXISTS completion_tokens INTEGER NOT NULL DEFAULT 0
        CHECK (completion_tokens >= 0),
    ADD COLUMN IF NOT EXISTS cost_usd NUMERIC(12, 6) NOT NULL DEFAULT 0
        CHECK (cost_usd >= 0),
    ADD COLUMN IF NOT EXISTS model_key VARCHAR(160),
    ADD COLUMN IF NOT EXISTS framework_key VARCHAR(160),
    ADD COLUMN IF NOT EXISTS error_code VARCHAR(64),
    ADD COLUMN IF NOT EXISTS error_message TEXT;

-- Status filter index: the /api/admin/runs endpoint needs to filter by status
-- and time. We previously had only (agent_key, started_at DESC).
CREATE INDEX IF NOT EXISTS agent_runs_status_started_idx
    ON agent_runs (status, started_at DESC);

-- Event-type index: the trace detail endpoint supports ?type= filtering.
CREATE INDEX IF NOT EXISTS agent_run_events_type_idx
    ON agent_run_events (event_type, occurred_at);

COMMIT;
