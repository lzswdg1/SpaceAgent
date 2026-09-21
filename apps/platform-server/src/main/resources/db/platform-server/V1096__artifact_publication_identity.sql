DO $artifact_publication_identity$
BEGIN
    IF to_regclass('public.platform_artifact_objects') IS NULL THEN RETURN; END IF;
    -- A publication owns its access, retention and deletion identity. Equal content
    -- must not alias a pending deletion or another uploader's private publication.
    -- Existing rows/storage keys remain unchanged; new keys are staging-scoped.
    ALTER TABLE platform_artifact_objects DROP CONSTRAINT uk_artifact_object_content;
    CREATE INDEX ix_artifact_object_content_lookup
        ON platform_artifact_objects(tenant_id,content_sha256,byte_size,encryption_reference);
END $artifact_publication_identity$;
