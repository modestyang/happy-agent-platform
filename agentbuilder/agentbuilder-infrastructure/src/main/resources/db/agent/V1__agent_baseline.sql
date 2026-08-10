-- Development baseline for the local personal Agent workbench.
-- Keep this file self-contained until the first production release freezes migration history.

CREATE TABLE agent_providers (
    provider_key VARCHAR(160) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    endpoint TEXT NOT NULL,
    protocol VARCHAR(40) NOT NULL DEFAULT 'OPENAI_COMPATIBLE'
        CHECK (protocol = 'OPENAI_COMPATIBLE'),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_models (
    model_key VARCHAR(160) PRIMARY KEY,
    provider_key VARCHAR(160) NOT NULL REFERENCES agent_providers(provider_key),
    model_id VARCHAR(240) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    description TEXT NOT NULL,
    supports_streaming BOOLEAN NOT NULL DEFAULT TRUE,
    supports_tool_calling BOOLEAN NOT NULL DEFAULT TRUE,
    supports_vision BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (provider_key, model_id),
    UNIQUE (provider_key, model_key)
);

CREATE INDEX agent_models_provider_status_idx
    ON agent_models(provider_key, status, display_name);

CREATE TABLE agent_prompts (
    prompt_key VARCHAR(160) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    description TEXT NOT NULL,
    template TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_skills (
    skill_key VARCHAR(160) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    description TEXT NOT NULL,
    when_to_use TEXT NOT NULL DEFAULT '',
    when_not_to_use TEXT NOT NULL DEFAULT '',
    content TEXT NOT NULL DEFAULT '',
    required_tool_keys JSONB NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(required_tool_keys) = 'array'),
    runtime_ready BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_hooks (
    hook_key VARCHAR(160) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    description TEXT NOT NULL,
    phase VARCHAR(40) NOT NULL,
    mandatory BOOLEAN NOT NULL DEFAULT FALSE,
    runtime_ready BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_frameworks (
    framework_key VARCHAR(160) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    description TEXT NOT NULL,
    capabilities JSONB NOT NULL DEFAULT '{}'::jsonb
        CHECK (jsonb_typeof(capabilities) = 'object'),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_memories (
    memory_key VARCHAR(160) PRIMARY KEY,
    display_name VARCHAR(160) NOT NULL,
    description TEXT NOT NULL,
    retention_hours INTEGER NOT NULL CHECK (retention_hours > 0),
    max_tokens INTEGER NOT NULL CHECK (max_tokens > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'DISABLED')),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO agent_providers(provider_key, display_name, endpoint) VALUES
    ('bailian', '阿里云百炼', 'https://dashscope.aliyuncs.com/compatible-mode/v1'),
    ('minimax', 'MiniMax', 'https://api.minimaxi.com/v1');

INSERT INTO agent_models(
    model_key, provider_key, model_id, display_name, description,
    supports_streaming, supports_tool_calling, supports_vision) VALUES
    ('qwen-plus', 'bailian', 'qwen-plus', '通义千问 Plus', '日常对话与工具调用主模型', TRUE, TRUE, FALSE),
    ('qwen-vl-plus', 'bailian', 'qwen-vl-plus', '通义千问 VL Plus', '饮食图片识别模型', TRUE, FALSE, TRUE),
    ('minimax-m3', 'minimax', 'MiniMax-M3', 'MiniMax M3', '健身 Agent 统一使用的多模态模型', TRUE, TRUE, TRUE);

INSERT INTO agent_prompts(prompt_key, display_name, description, template) VALUES
    ('fitness.coach.prompt', '瘦瘦系统提示词', '健身陪伴场景的角色、边界与输出约束',
     '你是“瘦瘦 AI 花爷”，用户的 AI 健身陪伴。请使用中文，语气亲切、自然、可执行。基于用户输入、当前目标和已授权数据提供建议；不确定时明确说明，不夸大效果。'),
    ('agent.default.prompt', '通用系统提示词', '新建 Agent 的基础角色、边界与表达规范',
     '你是一个可靠、清晰的通用 AI 助手。使用用户指定的语言回答；仅根据已提供的上下文和已授权能力作答，不编造事实或执行未授权操作。');

INSERT INTO agent_skills(
    skill_key, display_name, description, when_to_use, content, required_tool_keys, runtime_ready) VALUES
    ('fitness.plan.skill', '训练计划编排', '根据目标和历史负荷制定训练计划',
     '用户要求制定、调整或保存训练计划时',
     '# 训练计划编排\n\n1. 查询目标和训练记录\n2. 生成建议\n3. 请求用户确认后保存',
     '["fitness.profile.query","fitness.workout.query","fitness.plan.generate","fitness.plan.save"]'::jsonb, TRUE),
    ('fitness.meal.skill', '每日饮食建议', '结合训练与饮食记录推荐三餐',
     '用户需要饮食建议或复盘时',
     '# 每日饮食建议\n\n读取档案、训练、饮食和反馈后生成建议。',
     '["fitness.profile.query","fitness.workout.query","fitness.meal.query","fitness.meal.feedback_context"]'::jsonb, TRUE);

INSERT INTO agent_hooks(
    hook_key, display_name, description, phase, mandatory, runtime_ready) VALUES
    ('fitness.safety', '健身安全护栏', '模型调用前确定性拦截急性症状、受伤、极端节食与过度训练风险', 'BEFORE_MODEL', TRUE, TRUE);

INSERT INTO agent_frameworks(
    framework_key, display_name, description, capabilities) VALUES
    ('agentscope', 'AgentScope', 'AgentScope Java 运行时适配器', '{"tools":true,"skills":true,"hooks":true}'::jsonb);

INSERT INTO agent_memories(
    memory_key, display_name, description, retention_hours, max_tokens) VALUES
    ('fitness.daily-memory', '当日会话记忆', '24 小时会话窗口与摘要压缩策略', 24, 12000),
    ('agent.default.memory', '默认会话记忆', '通用 Agent 的 24 小时会话窗口', 24, 12000);

CREATE TABLE agent_drafts (
    agent_key VARCHAR(160) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    description TEXT NOT NULL,
    status VARCHAR(24) NOT NULL CHECK (status IN ('DRAFT','READY','PUBLISHED','BLOCKED')),
    framework_key VARCHAR(160) NOT NULL REFERENCES agent_frameworks(framework_key),
    provider_key VARCHAR(160) NOT NULL,
    model_key VARCHAR(160) NOT NULL,
    prompt_key VARCHAR(160) NOT NULL REFERENCES agent_prompts(prompt_key),
    tool_keys JSONB NOT NULL CHECK (jsonb_typeof(tool_keys) = 'array'),
    skill_keys JSONB NOT NULL CHECK (jsonb_typeof(skill_keys) = 'array'),
    hook_keys JSONB NOT NULL CHECK (jsonb_typeof(hook_keys) = 'array'),
    memory_key VARCHAR(160) NOT NULL REFERENCES agent_memories(memory_key),
    temperature NUMERIC(4,3) NOT NULL CHECK (temperature BETWEEN 0 AND 2),
    max_tool_calls INTEGER NOT NULL CHECK (max_tool_calls BETWEEN 1 AND 50),
    current_published_version INTEGER NOT NULL DEFAULT 0 CHECK (current_published_version >= 0),
    revision BIGINT NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (provider_key, model_key) REFERENCES agent_models(provider_key, model_key)
);

INSERT INTO agent_drafts(
    agent_key, name, description, status, framework_key, provider_key, model_key,
    prompt_key, tool_keys, skill_keys, hook_keys, memory_key, temperature, max_tool_calls) VALUES
    ('fitness.coach', '瘦瘦健身教练', '结合用户的训练、饮食与身体记录，提供可执行的日常陪伴。',
     'DRAFT', 'agentscope', 'minimax', 'minimax-m3', 'fitness.coach.prompt',
     '["fitness.profile.query","fitness.workout.query","fitness.meal.query","fitness.meal.feedback_context","fitness.plan.generate","fitness.plan.save"]'::jsonb,
     '["fitness.meal.skill","fitness.plan.skill"]'::jsonb,
     '["fitness.safety"]'::jsonb, 'fitness.daily-memory', 0.5, 8);

CREATE TABLE agent_provider_credentials (
    provider_key VARCHAR(160) PRIMARY KEY REFERENCES agent_providers(provider_key),
    credential_ciphertext BYTEA NOT NULL,
    credential_iv BYTEA NOT NULL CHECK (octet_length(credential_iv) = 12),
    credential_aad BYTEA NOT NULL,
    credential_key_version INTEGER NOT NULL DEFAULT 1 CHECK (credential_key_version > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_versions (
    agent_version_id UUID PRIMARY KEY,
    agent_key VARCHAR(160) NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    status VARCHAR(32) NOT NULL,
    configuration JSONB NOT NULL CHECK (jsonb_typeof(configuration) = 'object'),
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (agent_key, version)
);

CREATE TABLE agent_conversations (
    conversation_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    agent_key VARCHAR(160) NOT NULL,
    title VARCHAR(240) NOT NULL DEFAULT '',
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CLOSED')),
    started_at TIMESTAMPTZ NOT NULL,
    last_message_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ
);

CREATE INDEX agent_conversations_user_last_message_idx
    ON agent_conversations (user_id, last_message_at DESC);
CREATE INDEX agent_conversations_active_idx
    ON agent_conversations (user_id, agent_key, status, last_message_at DESC);
CREATE UNIQUE INDEX agent_conversations_one_active_idx
    ON agent_conversations (user_id, agent_key) WHERE status = 'ACTIVE';

CREATE TABLE agent_runs (
    run_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    conversation_id UUID NOT NULL REFERENCES agent_conversations(conversation_id),
    agent_key VARCHAR(160) NOT NULL,
    agent_version INTEGER NOT NULL CHECK (agent_version > 0),
    status VARCHAR(24) NOT NULL
        CHECK (status IN ('QUEUED','RUNNING','WAITING_APPROVAL','SUCCEEDED','FAILED','CANCELLED')),
    started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    duration_ms BIGINT NOT NULL DEFAULT 0 CHECK (duration_ms >= 0),
    tool_calls INTEGER NOT NULL DEFAULT 0 CHECK (tool_calls >= 0),
    input_summary TEXT NOT NULL DEFAULT '',
    output_summary TEXT NOT NULL DEFAULT '',
    prompt_tokens INTEGER NOT NULL DEFAULT 0 CHECK (prompt_tokens >= 0),
    completion_tokens INTEGER NOT NULL DEFAULT 0 CHECK (completion_tokens >= 0),
    cost_usd NUMERIC(12, 6) NOT NULL DEFAULT 0 CHECK (cost_usd >= 0),
    model_key VARCHAR(160),
    framework_key VARCHAR(160),
    error_code VARCHAR(64),
    error_message TEXT
);

CREATE INDEX agent_runs_agent_started_idx ON agent_runs(agent_key, started_at DESC);
CREATE INDEX agent_runs_status_started_idx ON agent_runs(status, started_at DESC);
CREATE INDEX agent_runs_conversation_started_idx ON agent_runs(conversation_id, started_at ASC);
CREATE INDEX agent_runs_user_started_idx ON agent_runs(user_id, started_at DESC);

CREATE TABLE agent_run_events (
    run_id UUID NOT NULL REFERENCES agent_runs(run_id) ON DELETE CASCADE,
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    event_type VARCHAR(40) NOT NULL,
    title VARCHAR(200) NOT NULL,
    detail TEXT NOT NULL DEFAULT '',
    payload JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(payload) = 'object'),
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (run_id, sequence)
);

CREATE INDEX agent_run_events_type_idx ON agent_run_events(event_type, occurred_at);

CREATE TABLE agent_conversation_messages (
    message_id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES agent_conversations(conversation_id) ON DELETE CASCADE,
    run_id UUID REFERENCES agent_runs(run_id) ON DELETE SET NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM')),
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX agent_conversation_messages_conversation_created_idx
    ON agent_conversation_messages(conversation_id, created_at ASC, message_id ASC);

CREATE TABLE agent_run_stream_events (
    run_id UUID NOT NULL REFERENCES agent_runs(run_id) ON DELETE CASCADE,
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    event_type VARCHAR(40) NOT NULL,
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    occurred_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (run_id, sequence)
);

CREATE TABLE agent_run_approvals (
    approval_id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES agent_runs(run_id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    tool_call_id UUID NOT NULL,
    tool_key VARCHAR(160) NOT NULL,
    title VARCHAR(160) NOT NULL,
    arguments JSONB NOT NULL CHECK (jsonb_typeof(arguments) = 'object'),
    status VARCHAR(24) NOT NULL CHECK (status IN ('REQUESTED','APPROVED','REJECTED')),
    decision_key VARCHAR(256),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decided_at TIMESTAMPTZ,
    UNIQUE (run_id, tool_call_id)
);

CREATE INDEX agent_run_approvals_run_status_idx
    ON agent_run_approvals(run_id, status, created_at);

CREATE TABLE agent_admin_accounts (
    account_id UUID PRIMARY KEY,
    username VARCHAR(80) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','DISABLED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_admin_sessions (
    session_token_hash CHAR(64) PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES agent_admin_accounts(account_id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX agent_admin_sessions_account_idx
    ON agent_admin_sessions(account_id, expires_at);

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

CREATE INDEX evaluation_jobs_lease_idx
    ON evaluation_jobs(status, lease_until, next_attempt_at);

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

CREATE INDEX probe_jobs_lease_idx ON probe_jobs(status, lease_until, next_attempt_at);

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

-- Legacy typed repositories remain internal and are not exposed as workbench resources.
CREATE TABLE skill_catalog (
    component_key VARCHAR(160) NOT NULL, version INTEGER NOT NULL CHECK (version > 0),
    application_scope VARCHAR(120) NOT NULL, status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','AVAILABLE','DEPRECATED','DISABLED','RETIRED')),
    revision BIGINT NOT NULL CHECK (revision > 0), checksum CHAR(64) NOT NULL,
    markdown TEXT NOT NULL, resources JSONB NOT NULL, disclosure JSONB NOT NULL, required_tools JSONB NOT NULL,
    skill_payload JSONB NOT NULL CHECK (jsonb_typeof(skill_payload) = 'object'), tags TEXT[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (component_key, version));
CREATE TABLE hook_catalog (
    component_key VARCHAR(160) NOT NULL, version INTEGER NOT NULL CHECK (version > 0), application_scope VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','AVAILABLE','DEPRECATED','DISABLED','RETIRED')), revision BIGINT NOT NULL CHECK (revision > 0), checksum CHAR(64) NOT NULL,
    hook_type VARCHAR(120) NOT NULL, phases TEXT[] NOT NULL, execution_order INTEGER NOT NULL, config_schema JSONB NOT NULL, failure_policy VARCHAR(32) NOT NULL, mandatory BOOLEAN NOT NULL,
    hook_payload JSONB NOT NULL CHECK (jsonb_typeof(hook_payload) = 'object'), tags TEXT[] NOT NULL DEFAULT '{}', PRIMARY KEY(component_key, version));
CREATE TABLE provider_catalog (
    component_key VARCHAR(160) NOT NULL, version INTEGER NOT NULL CHECK (version > 0), application_scope VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','AVAILABLE','DEPRECATED','DISABLED','RETIRED')), revision BIGINT NOT NULL CHECK (revision > 0), checksum CHAR(64) NOT NULL,
    display_name VARCHAR(160) NOT NULL, endpoint TEXT NOT NULL, public_config JSONB NOT NULL, provider_payload JSONB NOT NULL CHECK (jsonb_typeof(provider_payload) = 'object'),
    credential_ciphertext BYTEA NOT NULL, credential_iv BYTEA NOT NULL CHECK (octet_length(credential_iv)=12), credential_aad BYTEA NOT NULL,
    credential_key_version INTEGER NOT NULL, tags TEXT[] NOT NULL DEFAULT '{}', PRIMARY KEY(component_key, version));
CREATE TABLE model_catalog (
    component_key VARCHAR(160) NOT NULL, version INTEGER NOT NULL CHECK (version > 0), application_scope VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','AVAILABLE','DEPRECATED','DISABLED','RETIRED')), revision BIGINT NOT NULL CHECK (revision > 0), checksum CHAR(64) NOT NULL,
    provider_key VARCHAR(160) NOT NULL, provider_version INTEGER NOT NULL, model_id VARCHAR(240) NOT NULL, modalities TEXT[] NOT NULL, capabilities JSONB NOT NULL, limits JSONB NOT NULL,
    model_payload JSONB NOT NULL CHECK (jsonb_typeof(model_payload) = 'object'), tags TEXT[] NOT NULL DEFAULT '{}', PRIMARY KEY(component_key, version));
CREATE TABLE memory_policy_catalog (
    component_key VARCHAR(160) NOT NULL, version INTEGER NOT NULL CHECK (version > 0), application_scope VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','AVAILABLE','DEPRECATED','DISABLED','RETIRED')), revision BIGINT NOT NULL CHECK (revision > 0), checksum CHAR(64) NOT NULL,
    policy_type VARCHAR(32) NOT NULL, compression VARCHAR(32) NOT NULL, policy_config JSONB NOT NULL,
    memory_policy_payload JSONB NOT NULL CHECK (jsonb_typeof(memory_policy_payload) = 'object'), tags TEXT[] NOT NULL DEFAULT '{}', PRIMARY KEY(component_key, version));
CREATE TABLE prompt_catalog (
    component_key VARCHAR(160) NOT NULL, version INTEGER NOT NULL CHECK (version > 0), application_scope VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','AVAILABLE','DEPRECATED','DISABLED','RETIRED')), revision BIGINT NOT NULL CHECK (revision > 0), checksum CHAR(64) NOT NULL,
    template_format VARCHAR(32) NOT NULL, template TEXT NOT NULL, variable_schema JSONB NOT NULL, content_checksum CHAR(64) NOT NULL,
    prompt_payload JSONB NOT NULL CHECK (jsonb_typeof(prompt_payload) = 'object'), tags TEXT[] NOT NULL DEFAULT '{}', PRIMARY KEY(component_key, version));
CREATE TABLE output_schema_catalog (
    component_key VARCHAR(160) NOT NULL, version INTEGER NOT NULL CHECK (version > 0), application_scope VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','AVAILABLE','DEPRECATED','DISABLED','RETIRED')), revision BIGINT NOT NULL CHECK (revision > 0), checksum CHAR(64) NOT NULL,
    schema_payload JSONB NOT NULL, examples JSONB NOT NULL, content_checksum CHAR(64) NOT NULL,
    output_schema_payload JSONB NOT NULL CHECK (jsonb_typeof(output_schema_payload) = 'object'), tags TEXT[] NOT NULL DEFAULT '{}', PRIMARY KEY(component_key, version));
CREATE TABLE evaluation_suite_catalog (
    component_key VARCHAR(160) NOT NULL, version INTEGER NOT NULL CHECK (version > 0), application_scope VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','AVAILABLE','DEPRECATED','DISABLED','RETIRED')), revision BIGINT NOT NULL CHECK (revision > 0), checksum CHAR(64) NOT NULL,
    cases JSONB NOT NULL, scoring_config JSONB NOT NULL, safety_config JSONB NOT NULL, content_checksum CHAR(64) NOT NULL,
    evaluation_suite_payload JSONB NOT NULL CHECK (jsonb_typeof(evaluation_suite_payload) = 'object'), tags TEXT[] NOT NULL DEFAULT '{}', PRIMARY KEY(component_key, version));
CREATE TABLE framework_adapter_catalog (
    component_key VARCHAR(160) NOT NULL, version INTEGER NOT NULL CHECK (version > 0), application_scope VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','AVAILABLE','DEPRECATED','DISABLED','RETIRED')), revision BIGINT NOT NULL CHECK (revision > 0), checksum CHAR(64) NOT NULL,
    capabilities JSONB NOT NULL, framework_payload JSONB NOT NULL CHECK (jsonb_typeof(framework_payload) = 'object'), tags TEXT[] NOT NULL DEFAULT '{}', PRIMARY KEY(component_key, version));
CREATE TABLE default_profile_catalog (
    application_key VARCHAR(120) NOT NULL, profile_key VARCHAR(160) NOT NULL, version INTEGER NOT NULL CHECK (version > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','AVAILABLE','DEPRECATED','DISABLED','RETIRED')), revision BIGINT NOT NULL CHECK (revision > 0), checksum CHAR(64) NOT NULL,
    defaults JSONB NOT NULL, defaults_payload JSONB NOT NULL CHECK (jsonb_typeof(defaults_payload) = 'object'), tags TEXT[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(profile_key, version),
    UNIQUE(application_key, profile_key, version));
CREATE TABLE default_profile_active_pointer (
    application_key VARCHAR(120) PRIMARY KEY,
    profile_key VARCHAR(160) NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    revision BIGINT NOT NULL CHECK (revision > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(application_key, profile_key, version)
        REFERENCES default_profile_catalog(application_key, profile_key, version));
