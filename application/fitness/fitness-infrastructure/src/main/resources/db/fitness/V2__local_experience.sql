ALTER TABLE users ADD COLUMN username VARCHAR(80) UNIQUE;
ALTER TABLE users ADD COLUMN password_hash VARCHAR(100);
ALTER TABLE users ADD COLUMN nickname VARCHAR(80);

CREATE TABLE fitness_sessions (
    session_token_hash CHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX fitness_sessions_user_idx ON fitness_sessions(user_id, expires_at);

CREATE TABLE goals (
    goal_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    name VARCHAR(160) NOT NULL,
    start_weight_jin NUMERIC(6,2) NOT NULL,
    target_weight_jin NUMERIC(6,2) NOT NULL,
    target_date DATE,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX goals_user_created_idx ON goals(user_id, created_at DESC);

CREATE TABLE body_records (
    body_record_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    recorded_at TIMESTAMPTZ NOT NULL,
    weight_jin NUMERIC(6,2),
    waist_cm NUMERIC(6,2),
    CHECK (weight_jin IS NOT NULL OR waist_cm IS NOT NULL)
);
CREATE INDEX body_records_user_recorded_idx ON body_records(user_id, recorded_at DESC);

CREATE TABLE meals (
    meal_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    occurred_at TIMESTAMPTZ NOT NULL,
    meal_type VARCHAR(16) NOT NULL CHECK (meal_type IN ('BREAKFAST','LUNCH','DINNER','SNACK')),
    items JSONB NOT NULL CHECK (jsonb_typeof(items) = 'array')
);
CREATE INDEX meals_user_occurred_idx ON meals(user_id, occurred_at DESC);

CREATE TABLE exercises (
    exercise_id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    target_area VARCHAR(120) NOT NULL,
    sets INTEGER NOT NULL CHECK (sets > 0),
    seconds INTEGER NOT NULL CHECK (seconds > 0),
    steps JSONB NOT NULL CHECK (jsonb_typeof(steps) = 'array'),
    errors JSONB NOT NULL CHECK (jsonb_typeof(errors) = 'array'),
    image_urls JSONB NOT NULL CHECK (jsonb_typeof(image_urls) = 'array')
);

CREATE TABLE workout_plans (
    workout_plan_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    title VARCHAR(160) NOT NULL,
    estimated_minutes INTEGER NOT NULL CHECK (estimated_minutes > 0),
    status VARCHAR(32) NOT NULL,
    scheduled_for DATE NOT NULL,
    completion_ratio NUMERIC(4,3),
    completed_at TIMESTAMPTZ
);
CREATE INDEX workout_plans_user_schedule_idx ON workout_plans(user_id, scheduled_for DESC);

CREATE TABLE workout_plan_exercises (
    workout_plan_id UUID NOT NULL REFERENCES workout_plans(workout_plan_id) ON DELETE CASCADE,
    exercise_id UUID NOT NULL REFERENCES exercises(exercise_id),
    display_order INTEGER NOT NULL,
    PRIMARY KEY(workout_plan_id, exercise_id)
);
