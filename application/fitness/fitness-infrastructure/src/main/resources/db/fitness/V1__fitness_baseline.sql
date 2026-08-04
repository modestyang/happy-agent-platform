CREATE TABLE users (
    user_id UUID PRIMARY KEY,
    external_subject VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE fitness_jobs (
    job_id UUID PRIMARY KEY,
    job_type VARCHAR(80) NOT NULL,
    business_key VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    lease_owner VARCHAR(255),
    lease_until TIMESTAMPTZ,
    retry_count INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    input_checksum VARCHAR(128) NOT NULL,
    error_code VARCHAR(80),
    error_detail TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (job_type, business_key)
);

CREATE INDEX fitness_jobs_lease_idx ON fitness_jobs (status, lease_until, next_attempt_at);

CREATE TABLE fitness_idempotency (
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

CREATE TABLE fitness_operations (
    operation_id UUID PRIMARY KEY,
    principal_id VARCHAR(255) NOT NULL,
    use_case VARCHAR(120) NOT NULL,
    request_digest VARCHAR(128) NOT NULL,
    result_digest VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
