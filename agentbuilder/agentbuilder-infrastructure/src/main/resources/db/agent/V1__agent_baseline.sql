CREATE TABLE agent_versions (
    agent_version_id UUID PRIMARY KEY,
    agent_key VARCHAR(160) NOT NULL,
    version INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    configuration JSONB NOT NULL,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (agent_key, version)
);

CREATE TABLE evaluation_jobs (
    evaluation_job_id UUID PRIMARY KEY,
    agent_key VARCHAR(160) NOT NULL,
    agent_version INTEGER,
    input_checksum VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    lease_owner VARCHAR(255),
    lease_until TIMESTAMPTZ,
    retry_count INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    result_summary JSONB,
    error_detail TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX evaluation_jobs_lease_idx ON evaluation_jobs (status, lease_until, next_attempt_at);

CREATE TABLE probe_jobs (
    probe_job_id UUID PRIMARY KEY,
    provider_key VARCHAR(160) NOT NULL,
    model_key VARCHAR(160),
    probe_type VARCHAR(80) NOT NULL,
    input_checksum VARCHAR(128) NOT NULL,
    status VARCHAR(32) NOT NULL,
    lease_owner VARCHAR(255),
    lease_until TIMESTAMPTZ,
    retry_count INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    result_summary JSONB,
    error_detail TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX probe_jobs_lease_idx ON probe_jobs (status, lease_until, next_attempt_at);

CREATE TABLE agent_idempotency (
    idempotency_id UUID PRIMARY KEY,
    principal_id VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_digest VARCHAR(128) NOT NULL,
    response_status INTEGER,
    resource_id UUID,
    response_summary JSONB,
    expires_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (principal_id, idempotency_key)
);
