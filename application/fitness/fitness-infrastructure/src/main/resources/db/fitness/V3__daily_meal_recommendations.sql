CREATE TABLE daily_meal_recommendations (
    recommendation_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    recommendation_date DATE NOT NULL,
    meal_type VARCHAR(16) NOT NULL CHECK (meal_type IN ('BREAKFAST','LUNCH','DINNER')),
    items JSONB NOT NULL CHECK (jsonb_typeof(items) = 'array'),
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('READY','GENERATING','FAILED')),
    generated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (user_id, recommendation_date, meal_type)
);

CREATE INDEX meal_recommendations_user_date_idx
    ON daily_meal_recommendations(user_id, recommendation_date DESC);
