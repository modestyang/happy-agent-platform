-- Durable AI conversation context. Authentication sessions stay in the fitness schema.
CREATE TABLE agent_conversations (
    conversation_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    agent_key VARCHAR(160) NOT NULL,
    title VARCHAR(240) NOT NULL DEFAULT '',
    status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE'
        CHECK (status IN ('ACTIVE', 'CLOSED')),
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

-- The project is pre-release. Existing anonymous Run records are intentionally discarded rather
-- than pretending that they can be assigned to a user or a conversation.
TRUNCATE TABLE agent_run_events, agent_runs;
ALTER TABLE agent_runs
    ADD COLUMN user_id UUID NOT NULL,
    ADD COLUMN conversation_id UUID NOT NULL REFERENCES agent_conversations(conversation_id);

CREATE INDEX agent_runs_conversation_started_idx
    ON agent_runs (conversation_id, started_at ASC);
CREATE INDEX agent_runs_user_started_idx
    ON agent_runs (user_id, started_at DESC);

CREATE TABLE agent_conversation_messages (
    message_id UUID PRIMARY KEY,
    conversation_id UUID NOT NULL REFERENCES agent_conversations(conversation_id) ON DELETE CASCADE,
    run_id UUID REFERENCES agent_runs(run_id) ON DELETE SET NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('USER', 'ASSISTANT', 'SYSTEM')),
    content TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX agent_conversation_messages_conversation_created_idx
    ON agent_conversation_messages (conversation_id, created_at ASC, message_id ASC);
