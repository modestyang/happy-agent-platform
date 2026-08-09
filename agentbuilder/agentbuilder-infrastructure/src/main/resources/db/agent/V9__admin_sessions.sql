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

CREATE INDEX agent_admin_sessions_account_idx ON agent_admin_sessions(account_id, expires_at);
