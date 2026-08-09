CREATE TABLE fitness_idempotency_keys (
  user_id UUID NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
  operation VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(256) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  resource_id UUID NOT NULL,
  response_json JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (user_id, operation, idempotency_key)
);
