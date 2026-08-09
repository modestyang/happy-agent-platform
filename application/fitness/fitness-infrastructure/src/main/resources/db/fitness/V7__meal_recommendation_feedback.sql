ALTER TABLE meals ADD COLUMN created_at TIMESTAMPTZ;
UPDATE meals SET created_at = occurred_at WHERE created_at IS NULL;
ALTER TABLE meals ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE meals ALTER COLUMN created_at SET NOT NULL;

CREATE TABLE meal_recommendation_feedback (
    user_id UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    recommendation_id UUID NOT NULL REFERENCES daily_meal_recommendations(recommendation_id) ON DELETE CASCADE,
    sentiment VARCHAR(16) NOT NULL CHECK (sentiment IN ('LIKE','DISLIKE')),
    reason VARCHAR(16) CHECK (reason IN ('TASTE','PORTION','INGREDIENT','CALORIES','COOKING','OTHER')),
    note VARCHAR(300),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, recommendation_id)
);
CREATE INDEX meal_recommendation_feedback_user_updated_idx ON meal_recommendation_feedback(user_id, updated_at DESC);

-- A plan run belongs solely to a fitness user/date. It deliberately has no goal or Agent-schema
-- foreign key: generation state and output remain inside the fitness bounded context.
CREATE TABLE daily_meal_plan_runs (
    meal_plan_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    plan_date DATE NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('GENERATING','READY','FAILED')),
    generated_at TIMESTAMPTZ,
    failure_code VARCHAR(64),
    failure_message VARCHAR(1000),
    version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, plan_date)
);
CREATE INDEX daily_meal_plan_runs_user_date_idx ON daily_meal_plan_runs(user_id, plan_date DESC);
