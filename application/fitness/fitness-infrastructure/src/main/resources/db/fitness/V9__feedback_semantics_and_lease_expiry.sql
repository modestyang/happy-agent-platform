-- V8 established owner integrity. This migration makes every nullable feedback branch explicitly
-- boolean so PostgreSQL CHECK's NULL-is-allowed behavior cannot bypass the public contract.
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
                            AND char_length(btrim(note)) BETWEEN 1 AND 300
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
