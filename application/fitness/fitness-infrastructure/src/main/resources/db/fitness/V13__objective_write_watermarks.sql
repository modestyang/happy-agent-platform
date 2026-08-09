-- A report's computed_through is an observation watermark.  Business event time alone cannot
-- identify a late backfill, so each objective source also exposes a durable write watermark.
ALTER TABLE body_records ADD COLUMN created_at TIMESTAMPTZ;
UPDATE body_records SET created_at = recorded_at WHERE created_at IS NULL;
ALTER TABLE body_records ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE body_records ALTER COLUMN created_at SET NOT NULL;

ALTER TABLE workout_plans ADD COLUMN updated_at TIMESTAMPTZ;
UPDATE workout_plans
SET updated_at = COALESCE(completed_at, CURRENT_TIMESTAMP)
WHERE updated_at IS NULL;
ALTER TABLE workout_plans ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE workout_plans ALTER COLUMN updated_at SET NOT NULL;

CREATE INDEX body_records_user_created_idx ON body_records (user_id, created_at DESC);
CREATE INDEX meals_user_created_idx ON meals (user_id, created_at DESC);
CREATE INDEX workout_plans_user_updated_idx ON workout_plans (user_id, updated_at DESC);
