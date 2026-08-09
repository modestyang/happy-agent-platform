-- Worker ownership is deliberately local to fitness. A token plus monotonic version fences
-- stale workers after a lease expires; no goal or agent-schema relationship is introduced.
ALTER TABLE daily_meal_plan_runs
    ADD COLUMN lease_token UUID,
    ADD COLUMN lease_until TIMESTAMPTZ;

CREATE INDEX daily_meal_plan_runs_claim_idx
    ON daily_meal_plan_runs (status, lease_until, created_at);

-- PostgreSQL requires a directly matching key before a composite owner FK can protect feedback.
ALTER TABLE daily_meal_recommendations
    ADD CONSTRAINT daily_meal_recommendations_user_recommendation_key
        UNIQUE (user_id, recommendation_id);

ALTER TABLE meal_recommendation_feedback
    DROP CONSTRAINT meal_recommendation_feedback_user_id_fkey,
    DROP CONSTRAINT meal_recommendation_feedback_recommendation_id_fkey,
    ADD CONSTRAINT meal_recommendation_feedback_owned_recommendation_fkey
        FOREIGN KEY (user_id, recommendation_id)
        REFERENCES daily_meal_recommendations (user_id, recommendation_id)
        ON DELETE CASCADE,
    ADD CONSTRAINT meal_recommendation_feedback_semantics_check
        CHECK (
            (sentiment = 'LIKE' AND reason IS NULL AND note IS NULL)
            OR
            (sentiment = 'DISLIKE' AND (
                (reason IN ('TASTE','PORTION','INGREDIENT','CALORIES','COOKING'))
                OR
                (reason = 'OTHER' AND char_length(btrim(note)) BETWEEN 1 AND 300)
            ))
        );
