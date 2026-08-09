-- Current-goal reports are a durable fitness-owned workflow. Objective records remain independent
-- of a goal and are selected by their timestamp when a worker computes the immutable snapshot.
ALTER TABLE goals
    ADD COLUMN version INTEGER NOT NULL DEFAULT 1;

CREATE TABLE current_goal_reports (
    report_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    goal_id UUID NOT NULL REFERENCES goals(goal_id) ON DELETE CASCADE,
    goal_version INTEGER NOT NULL CHECK (goal_version >= 1),
    state VARCHAR(16) NOT NULL CHECK (state IN ('QUEUED','GENERATING','READY','STALE','FAILED')),
    window_start DATE NOT NULL,
    window_end DATE NOT NULL,
    deterministic_snapshot JSONB,
    narrative JSONB,
    computed_through TIMESTAMPTZ,
    failure_code VARCHAR(80),
    failure_message VARCHAR(1000),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, goal_id, goal_version),
    CHECK (
        (state IN ('QUEUED','GENERATING') AND deterministic_snapshot IS NULL AND narrative IS NULL)
        OR (state IN ('READY','STALE') AND deterministic_snapshot IS NOT NULL AND narrative IS NOT NULL AND computed_through IS NOT NULL)
        OR (state = 'FAILED' AND failure_code IS NOT NULL AND failure_message IS NOT NULL)
    )
);

CREATE INDEX current_goal_reports_claim_idx
    ON current_goal_reports (state, lease_until, created_at);
CREATE INDEX current_goal_reports_user_goal_idx
    ON current_goal_reports (user_id, goal_id, goal_version);
