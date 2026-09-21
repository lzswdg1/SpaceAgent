-- Explicit immutable AgentVersion Draft/Review/Publish/Deprecate lifecycle.
DO $explicit_agent_version_workflow$
BEGIN
    IF to_regclass('public.platform_agent_versions') IS NULL THEN
        RETURN;
    END IF;

    ALTER TABLE platform_agent_versions
        ADD COLUMN reviewed_by VARCHAR(36),
        ADD COLUMN reviewed_at TIMESTAMPTZ,
        ADD COLUMN published_by VARCHAR(36),
        ADD COLUMN published_at TIMESTAMPTZ,
        ADD COLUMN deprecated_by VARCHAR(36);

    UPDATE platform_agent_versions
    SET published_by = created_by,
        published_at = created_at
    WHERE status IN ('PUBLISHED', 'DEPRECATED')
      AND published_at IS NULL;

    UPDATE platform_agent_versions
    SET deprecated_by = created_by
    WHERE deprecated_at IS NOT NULL
      AND deprecated_by IS NULL;

    ALTER TABLE platform_agent_versions
        DROP CONSTRAINT ck_platform_agent_version_status;

    ALTER TABLE platform_agent_versions
        ADD CONSTRAINT ck_platform_agent_version_status
            CHECK (status IN ('DRAFT', 'IN_REVIEW', 'PUBLISHED', 'DEPRECATED')),
        ADD CONSTRAINT ck_platform_agent_version_review_evidence
            CHECK ((reviewed_by IS NULL) = (reviewed_at IS NULL)),
        ADD CONSTRAINT ck_platform_agent_version_publish_evidence
            CHECK ((published_by IS NULL) = (published_at IS NULL)),
        ADD CONSTRAINT ck_platform_agent_version_deprecation_evidence
            CHECK ((deprecated_by IS NULL) = (deprecated_at IS NULL));

    CREATE INDEX idx_platform_agent_version_status
        ON platform_agent_versions(agent_id, status, version_number DESC);

    COMMENT ON COLUMN platform_agent_versions.reviewed_at IS
        'DRAFT -> IN_REVIEW transition evidence; configuration remains immutable';
    COMMENT ON COLUMN platform_agent_versions.published_at IS
        'Publish evidence; legacy auto-published rows are backfilled from creation';
END
$explicit_agent_version_workflow$;
