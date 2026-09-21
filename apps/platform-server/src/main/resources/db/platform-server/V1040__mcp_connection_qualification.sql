DO $mcp_connection_qualification$
BEGIN
    IF to_regclass('public.platform_mcp_connections') IS NULL THEN
        RETURN;
    END IF;

    ALTER TABLE platform_mcp_connections
        DROP CONSTRAINT ck_mcp_connection_state,
        ADD CONSTRAINT ck_mcp_connection_state CHECK(state IN(
            'PENDING_AUTH', 'PENDING_VALIDATION', 'ACTIVE', 'DEGRADED', 'ERROR', 'REVOKED')),
        ADD COLUMN current_capability_snapshot_id UUID,
        ADD COLUMN last_qualified_at TIMESTAMPTZ,
        ADD COLUMN last_health_at TIMESTAMPTZ,
        ADD COLUMN last_error_code VARCHAR(100),
        ADD COLUMN consecutive_failures INTEGER NOT NULL DEFAULT 0,
        ADD CONSTRAINT ck_mcp_connection_failures CHECK (consecutive_failures >= 0),
        ADD CONSTRAINT ck_mcp_connection_qualification CHECK (
            current_capability_snapshot_id IS NULL OR last_qualified_at IS NOT NULL);

    CREATE TABLE platform_mcp_capability_snapshots (
        id                  UUID PRIMARY KEY,
        connection_id       UUID NOT NULL REFERENCES platform_mcp_connections(id) ON DELETE CASCADE,
        connection_revision BIGINT NOT NULL,
        protocol_version    VARCHAR(40) NOT NULL,
        server_name         VARCHAR(200) NOT NULL,
        server_title        VARCHAR(200),
        server_version      VARCHAR(100) NOT NULL,
        server_description  VARCHAR(1000),
        capabilities_json   JSONB NOT NULL,
        tools_json          JSONB NOT NULL,
        snapshot_sha256     CHAR(64) NOT NULL,
        observed_at         TIMESTAMPTZ NOT NULL,
        CONSTRAINT uk_mcp_capability_snapshot_connection_id UNIQUE (connection_id, id),
        CONSTRAINT ck_mcp_capability_snapshot_revision CHECK (connection_revision > 0),
        CONSTRAINT ck_mcp_capability_snapshot_protocol
            CHECK (protocol_version ~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'),
        CONSTRAINT ck_mcp_capability_snapshot_capabilities
            CHECK (jsonb_typeof(capabilities_json) = 'object'),
        CONSTRAINT ck_mcp_capability_snapshot_tools
            CHECK (jsonb_typeof(tools_json) = 'array'),
        CONSTRAINT ck_mcp_capability_snapshot_digest
            CHECK (snapshot_sha256 ~ '^[0-9a-f]{64}$')
    );

    CREATE TABLE platform_mcp_connection_observations (
        id                  UUID PRIMARY KEY,
        observation_sequence BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
        connection_id       UUID NOT NULL REFERENCES platform_mcp_connections(id) ON DELETE CASCADE,
        connection_revision BIGINT NOT NULL,
        outcome             VARCHAR(24) NOT NULL,
        latency_ms          BIGINT NOT NULL,
        protocol_version    VARCHAR(40),
        capability_snapshot_id UUID REFERENCES platform_mcp_capability_snapshots(id)
            ON DELETE SET NULL,
        safe_error_code     VARCHAR(100),
        observed_at         TIMESTAMPTZ NOT NULL,
        CONSTRAINT ck_mcp_connection_observation_revision CHECK (connection_revision > 0),
        CONSTRAINT ck_mcp_connection_observation_outcome
            CHECK (outcome IN ('SUCCEEDED', 'FAILED')),
        CONSTRAINT ck_mcp_connection_observation_latency
            CHECK (latency_ms >= 0 AND latency_ms <= 120000),
        CONSTRAINT ck_mcp_connection_observation_result CHECK (
            (outcome = 'SUCCEEDED' AND capability_snapshot_id IS NOT NULL
                AND safe_error_code IS NULL)
            OR
            (outcome = 'FAILED' AND capability_snapshot_id IS NULL
                AND safe_error_code IS NOT NULL))
    );

    CREATE INDEX idx_mcp_capability_snapshot_connection_time
        ON platform_mcp_capability_snapshots(connection_id, observed_at DESC);
    CREATE INDEX idx_mcp_connection_observation_connection_time
        ON platform_mcp_connection_observations(connection_id, observation_sequence DESC);
    CREATE INDEX idx_mcp_connection_state_health
        ON platform_mcp_connections(state, last_health_at, id)
        WHERE state <> 'REVOKED';

    COMMENT ON COLUMN platform_mcp_connections.current_capability_snapshot_id IS
        'Tooling-owned current immutable snapshot; application-fenced to the same Connection';
END
$mcp_connection_qualification$;
