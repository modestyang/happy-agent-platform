ALTER TABLE default_profile_catalog
    ADD CONSTRAINT default_profile_application_version_unique
    UNIQUE (application_key, profile_key, version);

CREATE TABLE default_profile_active_pointer (
    application_key VARCHAR(120) NOT NULL,
    profile_key VARCHAR(160) NOT NULL,
    version INTEGER NOT NULL CHECK (version > 0),
    revision BIGINT NOT NULL CHECK (revision > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(application_key),
    FOREIGN KEY(application_key, profile_key, version)
        REFERENCES default_profile_catalog(application_key, profile_key, version));

INSERT INTO default_profile_active_pointer(application_key, profile_key, version, revision)
SELECT application_key, profile_key, version, 1
FROM (
    SELECT DISTINCT ON (application_key)
        application_key, profile_key, version
    FROM default_profile_catalog
    WHERE active
    ORDER BY application_key, version DESC, profile_key
) selected_active_profiles;

UPDATE default_profile_catalog
SET defaults_payload = defaults_payload - 'active'
WHERE defaults_payload ? 'active';

DROP INDEX default_profile_one_active_idx;
ALTER TABLE default_profile_catalog DROP COLUMN active;
