DO $official_mcp_registry_sync$
BEGIN
    IF to_regclass('public.platform_mcp_marketplace_entries') IS NULL THEN
        RETURN;
    END IF;

    ALTER TABLE platform_mcp_marketplace_entries
        DROP CONSTRAINT fk_mcp_entry_current_version;
    ALTER TABLE platform_mcp_marketplace_entries
        ADD CONSTRAINT fk_mcp_entry_current_version
        FOREIGN KEY (id, current_version_id)
        REFERENCES platform_mcp_server_versions(entry_id, id)
        DEFERRABLE INITIALLY DEFERRED;

    CREATE UNIQUE INDEX uk_mcp_official_registry_name
        ON platform_mcp_marketplace_entries(registry_name)
        WHERE source_type = 'OFFICIAL_REGISTRY';

    CREATE TABLE platform_mcp_registry_sources (
        id                      UUID PRIMARY KEY,
        source_key              VARCHAR(64) NOT NULL UNIQUE,
        display_name            VARCHAR(120) NOT NULL,
        base_url                TEXT NOT NULL,
        enabled                 BOOLEAN NOT NULL,
        last_successful_sync_at TIMESTAMPTZ,
        revision                BIGINT NOT NULL DEFAULT 1,
        created_at              TIMESTAMPTZ NOT NULL,
        updated_at              TIMESTAMPTZ NOT NULL,
        CONSTRAINT ck_mcp_registry_source_revision CHECK (revision > 0),
        CONSTRAINT ck_mcp_registry_source_https CHECK (base_url ~ '^https://')
    );

    INSERT INTO platform_mcp_registry_sources(
        id, source_key, display_name, base_url, enabled, created_at, updated_at)
    VALUES (
        '10420000-0000-4000-8000-000000000001',
        'official',
        'Official MCP Registry',
        'https://registry.modelcontextprotocol.io',
        TRUE,
        clock_timestamp(),
        clock_timestamp());

    CREATE TABLE platform_mcp_registry_sync_jobs (
        id                  UUID PRIMARY KEY,
        source_id           UUID NOT NULL REFERENCES platform_mcp_registry_sources(id),
        requested_by        UUID NOT NULL,
        state               VARCHAR(24) NOT NULL,
        updated_since       TIMESTAMPTZ,
        watermark_at        TIMESTAMPTZ NOT NULL,
        fetched_count       INTEGER NOT NULL DEFAULT 0,
        snapshot_count      INTEGER NOT NULL DEFAULT 0,
        candidate_count     INTEGER NOT NULL DEFAULT 0,
        safe_error_code     VARCHAR(100),
        attempt             INTEGER NOT NULL DEFAULT 0,
        claim_owner         VARCHAR(160),
        claim_token         UUID,
        lease_until         TIMESTAMPTZ,
        created_at          TIMESTAMPTZ NOT NULL,
        started_at          TIMESTAMPTZ,
        updated_at          TIMESTAMPTZ NOT NULL,
        completed_at        TIMESTAMPTZ,
        CONSTRAINT ck_mcp_registry_sync_state
            CHECK (state IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
        CONSTRAINT ck_mcp_registry_sync_counts
            CHECK (fetched_count >= 0 AND snapshot_count >= 0 AND candidate_count >= 0),
        CONSTRAINT ck_mcp_registry_sync_attempt CHECK (attempt >= 0),
        CONSTRAINT ck_mcp_registry_sync_terminal CHECK (
            (state IN ('SUCCEEDED', 'FAILED')) = (completed_at IS NOT NULL)),
        CONSTRAINT ck_mcp_registry_sync_claim CHECK (
            (state = 'RUNNING') =
            (claim_owner IS NOT NULL AND claim_token IS NOT NULL AND lease_until IS NOT NULL))
    );

    CREATE UNIQUE INDEX uk_mcp_registry_one_active_sync
        ON platform_mcp_registry_sync_jobs(source_id)
        WHERE state IN ('PENDING', 'RUNNING');
    CREATE INDEX idx_mcp_registry_sync_jobs_state
        ON platform_mcp_registry_sync_jobs(state, created_at DESC);

    CREATE TABLE platform_mcp_registry_snapshots (
        id                    UUID PRIMARY KEY,
        source_id             UUID NOT NULL REFERENCES platform_mcp_registry_sources(id),
        sync_job_id           UUID NOT NULL REFERENCES platform_mcp_registry_sync_jobs(id),
        registry_name         VARCHAR(200) NOT NULL,
        registry_version      VARCHAR(255) NOT NULL,
        registry_status       VARCHAR(24) NOT NULL,
        status_message        VARCHAR(500),
        display_title         VARCHAR(100),
        description           VARCHAR(100) NOT NULL,
        manifest_schema_uri   TEXT,
        repository_uri        TEXT,
        manifest_json         JSONB NOT NULL,
        manifest_sha256       CHAR(64) NOT NULL,
        transports_json       JSONB NOT NULL,
        compatibility         VARCHAR(40) NOT NULL,
        compatibility_reason  VARCHAR(100),
        source_published_at   TIMESTAMPTZ,
        source_updated_at     TIMESTAMPTZ,
        fetched_at            TIMESTAMPTZ NOT NULL,
        CONSTRAINT uk_mcp_registry_snapshot_digest
            UNIQUE (source_id, registry_name, registry_version, manifest_sha256),
        CONSTRAINT ck_mcp_registry_snapshot_status
            CHECK (registry_status IN ('ACTIVE', 'DEPRECATED', 'DELETED')),
        CONSTRAINT ck_mcp_registry_snapshot_manifest_object
            CHECK (jsonb_typeof(manifest_json) = 'object'),
        CONSTRAINT ck_mcp_registry_snapshot_transports_array
            CHECK (jsonb_typeof(transports_json) = 'array'),
        CONSTRAINT ck_mcp_registry_snapshot_digest
            CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
        CONSTRAINT ck_mcp_registry_snapshot_compatibility
            CHECK (compatibility IN ('SUPPORTED_REMOTE', 'UNSUPPORTED_TRANSPORT', 'INVALID_METADATA'))
    );

    CREATE INDEX idx_mcp_registry_snapshots_identity
        ON platform_mcp_registry_snapshots(source_id, registry_name, registry_version, fetched_at DESC);

    CREATE TABLE platform_mcp_registry_candidates (
        id                    UUID PRIMARY KEY,
        snapshot_id           UUID NOT NULL UNIQUE REFERENCES platform_mcp_registry_snapshots(id),
        source_id             UUID NOT NULL REFERENCES platform_mcp_registry_sources(id),
        registry_name         VARCHAR(200) NOT NULL,
        registry_version      VARCHAR(255) NOT NULL,
        review_state          VARCHAR(32) NOT NULL,
        reviewed_by           UUID,
        review_reason         VARCHAR(500),
        reviewed_at           TIMESTAMPTZ,
        published_entry_id    UUID REFERENCES platform_mcp_marketplace_entries(id),
        published_version_id  UUID REFERENCES platform_mcp_server_versions(id),
        revision              BIGINT NOT NULL DEFAULT 1,
        created_at            TIMESTAMPTZ NOT NULL,
        updated_at            TIMESTAMPTZ NOT NULL,
        CONSTRAINT ck_mcp_registry_candidate_state
            CHECK (review_state IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED')),
        CONSTRAINT ck_mcp_registry_candidate_revision CHECK (revision > 0),
        CONSTRAINT ck_mcp_registry_candidate_review CHECK (
            (review_state = 'PENDING_REVIEW') =
            (reviewed_by IS NULL AND review_reason IS NULL AND reviewed_at IS NULL)),
        CONSTRAINT ck_mcp_registry_candidate_publication CHECK (
            (review_state = 'APPROVED') =
            (published_entry_id IS NOT NULL AND published_version_id IS NOT NULL))
    );

    CREATE INDEX idx_mcp_registry_candidates_review
        ON platform_mcp_registry_candidates(review_state, created_at DESC, id);
    CREATE INDEX idx_mcp_registry_candidates_identity
        ON platform_mcp_registry_candidates(source_id, registry_name, registry_version, created_at DESC);
END
$official_mcp_registry_sync$;
