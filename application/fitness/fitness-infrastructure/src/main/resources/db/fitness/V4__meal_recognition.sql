CREATE TABLE media_objects (
    media_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    object_key VARCHAR(512) NOT NULL UNIQUE,
    content_type VARCHAR(64) NOT NULL CHECK (content_type IN ('image/jpeg','image/png','image/webp')),
    content_length BIGINT NOT NULL CHECK (content_length > 0 AND content_length <= 10485760),
    sha256 CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','UPLOADED','FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE meal_recognition_jobs (
    job_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    media_id UUID NOT NULL REFERENCES media_objects(media_id) ON DELETE RESTRICT,
    meal_type VARCHAR(16) NOT NULL CHECK (meal_type IN ('BREAKFAST','LUNCH','DINNER','SNACK')),
    occurred_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    candidates JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(candidates) = 'array'),
    failure_code VARCHAR(80),
    failure_message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX meal_recognition_jobs_user_created_idx ON meal_recognition_jobs(user_id, created_at DESC);

ALTER TABLE meals ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'MANUAL' CHECK (source IN ('MANUAL','RECOGNITION_CONFIRMED'));
ALTER TABLE meals ADD COLUMN recognition_job_id UUID;
ALTER TABLE meals ADD COLUMN note VARCHAR(1000);
