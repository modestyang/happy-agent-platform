-- V9 closed nullable feedback branches. Keep its raw length boundary while requiring OTHER
-- notes to contain a PostgreSQL non-whitespace character, including when surrounded by whitespace.
ALTER TABLE meal_recommendation_feedback
    DROP CONSTRAINT meal_recommendation_feedback_semantics_check,
    ADD CONSTRAINT meal_recommendation_feedback_semantics_check
        CHECK (
            (
                (sentiment = 'LIKE' AND reason IS NULL AND note IS NULL)
                OR
                (
                    sentiment = 'DISLIKE'
                    AND reason IS NOT NULL
                    AND (
                        (
                            reason = 'OTHER'
                            AND note IS NOT NULL
                            AND char_length(note) BETWEEN 1 AND 300
                            AND note ~ '[^[:space:]]'
                        )
                        OR
                        (
                            reason IN ('TASTE','PORTION','INGREDIENT','CALORIES','COOKING')
                            AND (note IS NULL OR char_length(note) <= 300)
                        )
                    )
                )
            ) IS TRUE
        );
