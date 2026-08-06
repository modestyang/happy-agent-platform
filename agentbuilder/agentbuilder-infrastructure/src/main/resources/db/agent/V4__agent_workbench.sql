CREATE TABLE agent_drafts (
    agent_key VARCHAR(160) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('DRAFT','READY','PUBLISHED','BLOCKED')),
    framework_key VARCHAR(160) NOT NULL,
    provider_key VARCHAR(160) NOT NULL,
    model_key VARCHAR(160) NOT NULL,
    prompt_key VARCHAR(160) NOT NULL,
    tool_keys JSONB NOT NULL CHECK (jsonb_typeof(tool_keys) = 'array'),
    skill_keys JSONB NOT NULL CHECK (jsonb_typeof(skill_keys) = 'array'),
    hook_keys JSONB NOT NULL CHECK (jsonb_typeof(hook_keys) = 'array'),
    memory_key VARCHAR(160) NOT NULL,
    temperature NUMERIC(4,3) NOT NULL CHECK (temperature BETWEEN 0 AND 2),
    max_tool_calls INTEGER NOT NULL CHECK (max_tool_calls BETWEEN 1 AND 50),
    current_published_version INTEGER NOT NULL DEFAULT 0 CHECK (current_published_version >= 0),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_component_projection (
    component_type VARCHAR(24) NOT NULL CHECK (component_type IN ('FRAMEWORK','PROVIDER','MODEL','TOOL','SKILL','HOOK','MEMORY','PROMPT','OUTPUT','EVALUATION')),
    component_key VARCHAR(160) NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    display_name VARCHAR(160) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('DRAFT','AVAILABLE','DEPRECATED','DISABLED','UNAVAILABLE')),
    tags TEXT[] NOT NULL DEFAULT '{}',
    config JSONB NOT NULL CHECK (jsonb_typeof(config) = 'object'),
    source_checksum CHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (component_type, component_key, version)
);

CREATE INDEX agent_component_projection_search_idx
    ON agent_component_projection(component_type, status, component_key);
CREATE INDEX agent_component_projection_tags_idx
    ON agent_component_projection USING GIN(tags);

CREATE TABLE agent_provider_credentials (
    provider_key VARCHAR(160) PRIMARY KEY,
    credential_ciphertext BYTEA NOT NULL,
    credential_iv BYTEA NOT NULL CHECK (octet_length(credential_iv) = 12),
    credential_aad BYTEA NOT NULL,
    credential_key_version INTEGER NOT NULL DEFAULT 1 CHECK (credential_key_version > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_runs (
    run_id UUID PRIMARY KEY,
    agent_key VARCHAR(160) NOT NULL,
    agent_version INTEGER NOT NULL CHECK (agent_version > 0),
    status VARCHAR(24) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    duration_ms BIGINT NOT NULL DEFAULT 0 CHECK (duration_ms >= 0),
    tool_calls INTEGER NOT NULL DEFAULT 0 CHECK (tool_calls >= 0),
    input_summary TEXT NOT NULL DEFAULT '',
    output_summary TEXT NOT NULL DEFAULT ''
);

CREATE INDEX agent_runs_agent_started_idx ON agent_runs(agent_key, started_at DESC);

CREATE TABLE agent_run_events (
    run_id UUID NOT NULL REFERENCES agent_runs(run_id) ON DELETE CASCADE,
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    event_type VARCHAR(40) NOT NULL,
    title VARCHAR(200) NOT NULL,
    detail TEXT NOT NULL DEFAULT '',
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (run_id, sequence)
);
