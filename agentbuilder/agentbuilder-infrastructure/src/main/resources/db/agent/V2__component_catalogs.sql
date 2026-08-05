CREATE TABLE skill_catalog (
    component_key VARCHAR(160) NOT NULL, version INTEGER NOT NULL CHECK (version > 0),
    application_scope VARCHAR(120) NOT NULL, status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','AVAILABLE','DEPRECATED','DISABLED','RETIRED')),
    revision BIGINT NOT NULL CHECK (revision > 0), checksum CHAR(64) NOT NULL,
    markdown TEXT NOT NULL, resources JSONB NOT NULL, disclosure JSONB NOT NULL, required_tools JSONB NOT NULL,
    skill_payload JSONB NOT NULL CHECK (jsonb_typeof(skill_payload) = 'object'), tags TEXT[] NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY (component_key, version));
CREATE INDEX skill_catalog_scope_status_idx ON skill_catalog(application_scope, status);
CREATE INDEX skill_catalog_tags_idx ON skill_catalog USING GIN(tags);

CREATE TABLE hook_catalog (
    component_key VARCHAR(160) NOT NULL, version INTEGER NOT NULL CHECK (version > 0), application_scope VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','AVAILABLE','DEPRECATED','DISABLED','RETIRED')), revision BIGINT NOT NULL CHECK (revision > 0), checksum CHAR(64) NOT NULL,
    hook_type VARCHAR(120) NOT NULL, phases TEXT[] NOT NULL, execution_order INTEGER NOT NULL, config_schema JSONB NOT NULL, failure_policy VARCHAR(32) NOT NULL, mandatory BOOLEAN NOT NULL,
    hook_payload JSONB NOT NULL CHECK (jsonb_typeof(hook_payload) = 'object'), tags TEXT[] NOT NULL DEFAULT '{}', PRIMARY KEY(component_key, version));
CREATE INDEX hook_catalog_scope_status_idx ON hook_catalog(application_scope, status);
CREATE INDEX hook_catalog_tags_idx ON hook_catalog USING GIN(tags);

CREATE TABLE provider_catalog (
    component_key VARCHAR(160) NOT NULL, version INTEGER NOT NULL CHECK (version > 0), application_scope VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','AVAILABLE','DEPRECATED','DISABLED','RETIRED')), revision BIGINT NOT NULL CHECK (revision > 0), checksum CHAR(64) NOT NULL,
    display_name VARCHAR(160) NOT NULL, endpoint TEXT NOT NULL, public_config JSONB NOT NULL,
    provider_payload JSONB NOT NULL CHECK (jsonb_typeof(provider_payload) = 'object'),
    credential_ciphertext BYTEA NOT NULL, credential_iv BYTEA NOT NULL CHECK (octet_length(credential_iv)=12), credential_aad BYTEA NOT NULL,
    credential_key_version INTEGER NOT NULL, tags TEXT[] NOT NULL DEFAULT '{}', PRIMARY KEY(component_key, version));
CREATE INDEX provider_catalog_scope_status_idx ON provider_catalog(application_scope, status);
CREATE INDEX provider_catalog_tags_idx ON provider_catalog USING GIN(tags);

CREATE TABLE model_catalog (
    component_key VARCHAR(160) NOT NULL, version INTEGER NOT NULL CHECK (version > 0), application_scope VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','AVAILABLE','DEPRECATED','DISABLED','RETIRED')), revision BIGINT NOT NULL CHECK (revision > 0), checksum CHAR(64) NOT NULL,
    provider_key VARCHAR(160) NOT NULL, provider_version INTEGER NOT NULL, model_id VARCHAR(240) NOT NULL, modalities TEXT[] NOT NULL, capabilities JSONB NOT NULL, limits JSONB NOT NULL,
    model_payload JSONB NOT NULL CHECK (jsonb_typeof(model_payload) = 'object'), tags TEXT[] NOT NULL DEFAULT '{}', PRIMARY KEY(component_key, version));
CREATE INDEX model_catalog_scope_status_idx ON model_catalog(application_scope, status);
CREATE INDEX model_catalog_tags_idx ON model_catalog USING GIN(tags);

CREATE TABLE memory_policy_catalog (
    component_key VARCHAR(160) NOT NULL, version INTEGER NOT NULL CHECK (version > 0), application_scope VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','AVAILABLE','DEPRECATED','DISABLED','RETIRED')), revision BIGINT NOT NULL CHECK (revision > 0), checksum CHAR(64) NOT NULL,
    policy_type VARCHAR(32) NOT NULL, compression VARCHAR(32) NOT NULL, policy_config JSONB NOT NULL,
    memory_policy_payload JSONB NOT NULL CHECK (jsonb_typeof(memory_policy_payload) = 'object'), tags TEXT[] NOT NULL DEFAULT '{}', PRIMARY KEY(component_key, version));
CREATE INDEX memory_policy_catalog_scope_status_idx ON memory_policy_catalog(application_scope, status);
CREATE INDEX memory_policy_catalog_tags_idx ON memory_policy_catalog USING GIN(tags);

CREATE TABLE prompt_catalog (
    component_key VARCHAR(160) NOT NULL, version INTEGER NOT NULL CHECK (version > 0), application_scope VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','AVAILABLE','DEPRECATED','DISABLED','RETIRED')), revision BIGINT NOT NULL CHECK (revision > 0), checksum CHAR(64) NOT NULL,
    template_format VARCHAR(32) NOT NULL, template TEXT NOT NULL, variable_schema JSONB NOT NULL, content_checksum CHAR(64) NOT NULL,
    prompt_payload JSONB NOT NULL CHECK (jsonb_typeof(prompt_payload) = 'object'), tags TEXT[] NOT NULL DEFAULT '{}', PRIMARY KEY(component_key, version));
CREATE INDEX prompt_catalog_scope_status_idx ON prompt_catalog(application_scope, status);
CREATE INDEX prompt_catalog_tags_idx ON prompt_catalog USING GIN(tags);

CREATE TABLE output_schema_catalog (
    component_key VARCHAR(160) NOT NULL, version INTEGER NOT NULL CHECK (version > 0), application_scope VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','AVAILABLE','DEPRECATED','DISABLED','RETIRED')), revision BIGINT NOT NULL CHECK (revision > 0), checksum CHAR(64) NOT NULL,
    schema_payload JSONB NOT NULL, examples JSONB NOT NULL, content_checksum CHAR(64) NOT NULL,
    output_schema_payload JSONB NOT NULL CHECK (jsonb_typeof(output_schema_payload) = 'object'), tags TEXT[] NOT NULL DEFAULT '{}', PRIMARY KEY(component_key, version));
CREATE INDEX output_schema_catalog_scope_status_idx ON output_schema_catalog(application_scope, status);
CREATE INDEX output_schema_catalog_tags_idx ON output_schema_catalog USING GIN(tags);

CREATE TABLE evaluation_suite_catalog (
    component_key VARCHAR(160) NOT NULL, version INTEGER NOT NULL CHECK (version > 0), application_scope VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','AVAILABLE','DEPRECATED','DISABLED','RETIRED')), revision BIGINT NOT NULL CHECK (revision > 0), checksum CHAR(64) NOT NULL,
    cases JSONB NOT NULL, scoring_config JSONB NOT NULL, safety_config JSONB NOT NULL, content_checksum CHAR(64) NOT NULL,
    evaluation_suite_payload JSONB NOT NULL CHECK (jsonb_typeof(evaluation_suite_payload) = 'object'), tags TEXT[] NOT NULL DEFAULT '{}', PRIMARY KEY(component_key, version));
CREATE INDEX evaluation_suite_catalog_scope_status_idx ON evaluation_suite_catalog(application_scope, status);
CREATE INDEX evaluation_suite_catalog_tags_idx ON evaluation_suite_catalog USING GIN(tags);

CREATE TABLE framework_adapter_catalog (
    component_key VARCHAR(160) NOT NULL, version INTEGER NOT NULL CHECK (version > 0), application_scope VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','AVAILABLE','DEPRECATED','DISABLED','RETIRED')), revision BIGINT NOT NULL CHECK (revision > 0), checksum CHAR(64) NOT NULL,
    capabilities JSONB NOT NULL,
    framework_payload JSONB NOT NULL CHECK (jsonb_typeof(framework_payload) = 'object'), tags TEXT[] NOT NULL DEFAULT '{}', PRIMARY KEY(component_key, version));
CREATE INDEX framework_adapter_catalog_scope_status_idx ON framework_adapter_catalog(application_scope, status);
CREATE INDEX framework_adapter_catalog_tags_idx ON framework_adapter_catalog USING GIN(tags);

CREATE TABLE default_profile_catalog (
    application_key VARCHAR(120) NOT NULL, profile_key VARCHAR(160) NOT NULL, version INTEGER NOT NULL CHECK (version > 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT','AVAILABLE','DEPRECATED','DISABLED','RETIRED')), revision BIGINT NOT NULL CHECK (revision > 0), checksum CHAR(64) NOT NULL,
    defaults JSONB NOT NULL, defaults_payload JSONB NOT NULL CHECK (jsonb_typeof(defaults_payload) = 'object'), tags TEXT[] NOT NULL DEFAULT '{}',
    active BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(profile_key, version));
CREATE UNIQUE INDEX default_profile_one_active_idx ON default_profile_catalog(application_key) WHERE active;
CREATE INDEX default_profile_status_idx ON default_profile_catalog(application_key, status);
CREATE INDEX default_profile_tags_idx ON default_profile_catalog USING GIN(tags);
