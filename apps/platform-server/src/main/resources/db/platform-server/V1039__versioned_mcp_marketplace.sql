DO $versioned_mcp_marketplace$
BEGIN
    IF to_regclass('public.platform_mcp_marketplace_entries') IS NULL THEN
        RETURN;
    END IF;

    CREATE TABLE platform_mcp_publishers (
        id                 UUID PRIMARY KEY,
        namespace          VARCHAR(200) NOT NULL UNIQUE,
        display_name       VARCHAR(160) NOT NULL,
        source_type        VARCHAR(32) NOT NULL,
        verification_state VARCHAR(32) NOT NULL,
        created_at         TIMESTAMPTZ NOT NULL,
        updated_at         TIMESTAMPTZ NOT NULL,
        CONSTRAINT ck_mcp_publisher_source
            CHECK (source_type IN ('BUILT_IN', 'OFFICIAL_REGISTRY', 'PRIVATE')),
        CONSTRAINT ck_mcp_publisher_verification
            CHECK (verification_state IN (
                'PLATFORM_CURATED', 'REGISTRY_VERIFIED', 'UNVERIFIED', 'BLOCKED'))
    );

    INSERT INTO platform_mcp_publishers(
        id, namespace, display_name, source_type, verification_state, created_at, updated_at)
    VALUES
        ('10390000-0000-4000-8000-000000000001', 'com.github', 'GitHub',
         'BUILT_IN', 'PLATFORM_CURATED', clock_timestamp(), clock_timestamp()),
        ('10390000-0000-4000-8000-000000000002', 'ai.spaceagent.local', 'SpaceAgent',
         'BUILT_IN', 'PLATFORM_CURATED', clock_timestamp(), clock_timestamp());

    ALTER TABLE platform_mcp_marketplace_entries
        ADD COLUMN publisher_id UUID,
        ADD COLUMN registry_name VARCHAR(240),
        ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'BUILT_IN',
        ADD COLUMN trust_tier VARCHAR(32) NOT NULL DEFAULT 'PLATFORM_CURATED',
        ADD COLUMN lifecycle_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
        ADD COLUMN current_version_id UUID,
        ADD COLUMN revision BIGINT NOT NULL DEFAULT 1,
        ADD CONSTRAINT ck_mcp_entry_source
            CHECK (source_type IN ('BUILT_IN', 'OFFICIAL_REGISTRY', 'PRIVATE')),
        ADD CONSTRAINT ck_mcp_entry_trust
            CHECK (trust_tier IN (
                'PLATFORM_CURATED', 'REGISTRY_VERIFIED', 'UNVERIFIED', 'QUARANTINED')),
        ADD CONSTRAINT ck_mcp_entry_lifecycle
            CHECK (lifecycle_state IN ('ACTIVE', 'DEPRECATED', 'REVOKED')),
        ADD CONSTRAINT ck_mcp_entry_revision CHECK (revision > 0);

    UPDATE platform_mcp_marketplace_entries
       SET publisher_id = CASE slug
                WHEN 'github' THEN '10390000-0000-4000-8000-000000000001'::uuid
                ELSE '10390000-0000-4000-8000-000000000002'::uuid
           END,
           source_type = CASE
                WHEN slug IN ('github', 'custom-streamable-http') THEN 'BUILT_IN'
                ELSE 'PRIVATE'
           END,
           trust_tier = CASE
                WHEN slug IN ('github', 'custom-streamable-http') THEN 'PLATFORM_CURATED'
                ELSE 'UNVERIFIED'
           END;

    ALTER TABLE platform_mcp_marketplace_entries
        ALTER COLUMN publisher_id SET NOT NULL,
        ADD CONSTRAINT fk_mcp_entry_publisher
            FOREIGN KEY (publisher_id) REFERENCES platform_mcp_publishers(id);

    CREATE TABLE platform_mcp_server_versions (
        id                  UUID PRIMARY KEY,
        entry_id            UUID NOT NULL REFERENCES platform_mcp_marketplace_entries(id),
        version             VARCHAR(80) NOT NULL,
        source_type         VARCHAR(32) NOT NULL,
        source_uri          TEXT,
        manifest_schema_uri TEXT,
        manifest_json       JSONB NOT NULL,
        manifest_sha256     CHAR(64) NOT NULL,
        auth_type           VARCHAR(24) NOT NULL,
        lifecycle_state     VARCHAR(32) NOT NULL,
        published_at        TIMESTAMPTZ,
        created_at          TIMESTAMPTZ NOT NULL,
        CONSTRAINT uk_mcp_server_version UNIQUE (entry_id, version),
        CONSTRAINT uk_mcp_server_version_entry_id UNIQUE (entry_id, id),
        CONSTRAINT ck_mcp_server_version_source
            CHECK (source_type IN ('BUILT_IN', 'OFFICIAL_REGISTRY', 'PRIVATE')),
        CONSTRAINT ck_mcp_server_version_manifest_object
            CHECK (jsonb_typeof(manifest_json) = 'object'),
        CONSTRAINT ck_mcp_server_version_digest
            CHECK (manifest_sha256 ~ '^[0-9a-f]{64}$'),
        CONSTRAINT ck_mcp_server_version_auth
            CHECK (auth_type IN ('NONE', 'OAUTH2', 'BEARER', 'CUSTOM')),
        CONSTRAINT ck_mcp_server_version_lifecycle
            CHECK (lifecycle_state IN ('CANDIDATE', 'APPROVED', 'DEPRECATED', 'REVOKED'))
    );

    INSERT INTO platform_mcp_server_versions(
        id, entry_id, version, source_type, source_uri, manifest_schema_uri,
        manifest_json, manifest_sha256, auth_type, lifecycle_state, published_at, created_at)
    SELECT
        CASE slug
            WHEN 'github' THEN '10390000-0000-4000-8000-000000000101'::uuid
            WHEN 'custom-streamable-http' THEN '10390000-0000-4000-8000-000000000102'::uuid
            ELSE gen_random_uuid()
        END,
        id,
        '1.0.0',
        source_type,
        CASE slug WHEN 'github' THEN 'https://github.com/github/github-mcp-server' END,
        NULL,
        manifest_json,
        encode(sha256(convert_to(manifest_json::text, 'UTF8')), 'hex'),
        auth_type,
        CASE
            WHEN slug IN ('github', 'custom-streamable-http') THEN 'APPROVED'
            ELSE 'CANDIDATE'
        END,
        updated_at,
        clock_timestamp()
    FROM platform_mcp_marketplace_entries;

    CREATE TABLE platform_mcp_server_transports (
        id                    UUID PRIMARY KEY,
        server_version_id     UUID NOT NULL REFERENCES platform_mcp_server_versions(id),
        position              INTEGER NOT NULL,
        transport_type        VARCHAR(32) NOT NULL,
        endpoint_template     TEXT,
        endpoint_configurable BOOLEAN NOT NULL,
        variables_schema_json JSONB NOT NULL DEFAULT '{}'::jsonb,
        headers_schema_json   JSONB NOT NULL DEFAULT '{}'::jsonb,
        enabled               BOOLEAN NOT NULL DEFAULT TRUE,
        created_at            TIMESTAMPTZ NOT NULL,
        CONSTRAINT uk_mcp_server_transport_position UNIQUE (server_version_id, position),
        CONSTRAINT ck_mcp_server_transport_position CHECK (position >= 0),
        CONSTRAINT ck_mcp_server_transport_type CHECK (transport_type IN ('STREAMABLE_HTTP')),
        CONSTRAINT ck_mcp_server_transport_endpoint CHECK (
            endpoint_configurable OR endpoint_template IS NOT NULL),
        CONSTRAINT ck_mcp_server_transport_variable_schema CHECK (
            jsonb_typeof(variables_schema_json) = 'object'),
        CONSTRAINT ck_mcp_server_transport_header_schema CHECK (
            jsonb_typeof(headers_schema_json) = 'object')
    );

    INSERT INTO platform_mcp_server_transports(
        id, server_version_id, position, transport_type, endpoint_template,
        endpoint_configurable, variables_schema_json, headers_schema_json, enabled, created_at)
    SELECT
        CASE entry.slug
            WHEN 'github' THEN '10390000-0000-4000-8000-000000000201'::uuid
            WHEN 'custom-streamable-http' THEN '10390000-0000-4000-8000-000000000202'::uuid
            ELSE gen_random_uuid()
        END,
        version.id,
        0,
        entry.transport,
        entry.default_endpoint,
        entry.default_endpoint IS NULL,
        '{}'::jsonb,
        '{}'::jsonb,
        TRUE,
        clock_timestamp()
    FROM platform_mcp_marketplace_entries entry
    JOIN platform_mcp_server_versions version ON version.entry_id = entry.id;

    UPDATE platform_mcp_marketplace_entries entry
       SET current_version_id = version.id
      FROM platform_mcp_server_versions version
     WHERE version.entry_id = entry.id;

    ALTER TABLE platform_mcp_marketplace_entries
        ALTER COLUMN current_version_id SET NOT NULL,
        ADD CONSTRAINT fk_mcp_entry_current_version
            FOREIGN KEY (id, current_version_id)
            REFERENCES platform_mcp_server_versions(entry_id, id);

    ALTER TABLE platform_mcp_installations
        ADD COLUMN server_version_id UUID;

    UPDATE platform_mcp_installations installation
       SET server_version_id = entry.current_version_id
      FROM platform_mcp_marketplace_entries entry
     WHERE entry.id = installation.entry_id;

    ALTER TABLE platform_mcp_installations
        ALTER COLUMN server_version_id SET NOT NULL,
        ADD CONSTRAINT fk_mcp_installation_server_version
            FOREIGN KEY (entry_id, server_version_id)
            REFERENCES platform_mcp_server_versions(entry_id, id);

    CREATE INDEX idx_mcp_server_versions_entry_state
        ON platform_mcp_server_versions(entry_id, lifecycle_state, created_at DESC);
    CREATE INDEX idx_mcp_server_transports_version_enabled
        ON platform_mcp_server_transports(server_version_id, enabled, position);
    CREATE INDEX idx_mcp_installations_server_version
        ON platform_mcp_installations(server_version_id);

    COMMENT ON COLUMN platform_mcp_marketplace_entries.manifest_json IS
        'Compatibility projection of the current immutable server version manifest';
    COMMENT ON COLUMN platform_mcp_marketplace_entries.default_endpoint IS
        'Compatibility projection of the current primary remote transport endpoint';
END
$versioned_mcp_marketplace$;
