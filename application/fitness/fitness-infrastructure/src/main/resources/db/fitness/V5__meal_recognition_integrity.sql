ALTER TABLE media_objects ADD COLUMN expires_at TIMESTAMPTZ;
UPDATE media_objects SET expires_at = created_at + INTERVAL '10 minutes' WHERE expires_at IS NULL;
ALTER TABLE media_objects ALTER COLUMN expires_at SET NOT NULL;

ALTER TABLE meals ADD CONSTRAINT meals_recognition_source_check CHECK (
  (source = 'MANUAL' AND recognition_job_id IS NULL)
  OR (source = 'RECOGNITION_CONFIRMED' AND recognition_job_id IS NOT NULL)
);
CREATE UNIQUE INDEX meals_recognition_job_unique ON meals(recognition_job_id)
  WHERE recognition_job_id IS NOT NULL;
