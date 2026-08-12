ALTER TABLE user_training_profiles
    ADD COLUMN coaching_tone VARCHAR(24) NOT NULL DEFAULT 'WARM_DIRECT'
        CHECK (coaching_tone IN ('WARM_DIRECT', 'LIGHT_HEARTED', 'CALM_PROFESSIONAL')),
    ADD COLUMN nutrition_preferences JSONB NOT NULL DEFAULT '[]'::jsonb;
