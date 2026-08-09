-- Define OTHER-note whitespace explicitly so PostgreSQL applies the same code point policy as
-- the public contract and service. char_length keeps the existing raw 1..300 character boundary.
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
                            AND note ~ U&'[^\0009-\000D\0020\0085\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]'
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
