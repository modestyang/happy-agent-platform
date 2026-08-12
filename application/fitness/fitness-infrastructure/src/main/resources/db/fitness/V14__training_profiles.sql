CREATE TABLE user_training_profiles (
    user_id UUID PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
    biological_sex VARCHAR(24) NOT NULL CHECK (biological_sex IN ('FEMALE', 'MALE', 'NOT_DISCLOSED')),
    birth_year INTEGER CHECK (birth_year BETWEEN 1900 AND 2100),
    height_cm NUMERIC(5,1) CHECK (height_cm BETWEEN 80 AND 250),
    experience_level VARCHAR(24) NOT NULL CHECK (experience_level IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED')),
    training_venues JSONB NOT NULL DEFAULT '[]'::jsonb,
    available_equipment JSONB NOT NULL DEFAULT '[]'::jsonb,
    training_weekdays JSONB NOT NULL DEFAULT '[]'::jsonb,
    session_minutes INTEGER NOT NULL CHECK (session_minutes BETWEEN 10 AND 180),
    training_restrictions JSONB NOT NULL DEFAULT '[]'::jsonb,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
