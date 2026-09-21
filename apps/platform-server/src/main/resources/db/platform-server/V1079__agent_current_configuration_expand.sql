-- M74 expand phase: add the replacement authorities before any AgentVersion consumer is removed.
-- Agent owns one mutable current configuration; Runtime owns one immutable configuration snapshot per Run.

CREATE TABLE platform_agent_current_configurations (
    agent_id                 VARCHAR(36) PRIMARY KEY,
    tenant_id                VARCHAR(64) NOT NULL,
    owner_id                 VARCHAR(36) NOT NULL,
    agent_revision           BIGINT NOT NULL,
    config_hash              CHAR(64) NOT NULL,
    system_prompt            TEXT,
    model_pool_id            UUID,
    model_provider_id        VARCHAR(100),
    model_id                 VARCHAR(160),
    temperature              DOUBLE PRECISION NOT NULL,
    max_context_tokens       INTEGER NOT NULL,
    max_output_tokens        INTEGER NOT NULL,
    max_turns                INTEGER NOT NULL,
    permission_mode          VARCHAR(20) NOT NULL,
    memory_enabled           BOOLEAN NOT NULL,
    rag_enabled              BOOLEAN NOT NULL,
    network_enabled          BOOLEAN NOT NULL,
    knowledge_base_ids       JSONB NOT NULL,
    enabled_tool_ids         JSONB NOT NULL,
    skill_ids                JSONB NOT NULL,
    updated_by               VARCHAR(36) NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_agent_current_configuration_scope
        UNIQUE (agent_id, tenant_id, owner_id),
    CONSTRAINT fk_agent_current_configuration_agent
        FOREIGN KEY (agent_id, tenant_id, owner_id)
        REFERENCES platform_agent_definitions(id, tenant_id, owner_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_agent_current_configuration_model_pool
        FOREIGN KEY (model_pool_id) REFERENCES platform_model_pools(id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_current_configuration_revision CHECK (agent_revision > 0),
    CONSTRAINT ck_agent_current_configuration_hash CHECK (config_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_current_configuration_temperature CHECK (temperature BETWEEN 0.0 AND 2.0),
    CONSTRAINT ck_agent_current_configuration_limits CHECK (
        max_context_tokens > 0 AND max_output_tokens > 0 AND max_turns > 0),
    CONSTRAINT ck_agent_current_configuration_permission CHECK (
        permission_mode IN ('private', 'auto', 'ask', 'deny')),
    CONSTRAINT ck_agent_current_configuration_model_binding CHECK (
        (model_pool_id IS NOT NULL AND model_provider_id IS NULL AND model_id IS NULL)
        OR
        (model_pool_id IS NULL AND (
            (model_provider_id IS NULL AND model_id IS NULL)
            OR (model_provider_id IS NOT NULL AND model_id IS NOT NULL)))),
    CONSTRAINT ck_agent_current_configuration_arrays CHECK (
        jsonb_typeof(knowledge_base_ids) = 'array'
        AND jsonb_typeof(enabled_tool_ids) = 'array'
        AND jsonb_typeof(skill_ids) = 'array')
);

WITH ranked AS (
    SELECT version.*,
           row_number() OVER (
               PARTITION BY version.agent_id
               ORDER BY version.version_number DESC, version.created_at DESC, version.id DESC
           ) AS position
    FROM platform_agent_versions version
)
INSERT INTO platform_agent_current_configurations (
    agent_id, tenant_id, owner_id, agent_revision, config_hash,
    system_prompt, model_pool_id, model_provider_id, model_id, temperature,
    max_context_tokens, max_output_tokens, max_turns, permission_mode,
    memory_enabled, rag_enabled, network_enabled,
    knowledge_base_ids, enabled_tool_ids, skill_ids, updated_by, updated_at
)
SELECT definition.id, definition.tenant_id, definition.owner_id, definition.revision,
       ranked.config_hash, ranked.system_prompt, ranked.model_pool_id,
       ranked.model_provider_id, ranked.model_id, ranked.temperature,
       ranked.max_context_tokens, ranked.max_output_tokens, ranked.max_turns,
       ranked.permission_mode, ranked.memory_enabled, ranked.rag_enabled,
       ranked.network_enabled, ranked.knowledge_base_ids, ranked.enabled_tool_ids,
       ranked.skill_ids, ranked.created_by, ranked.created_at
FROM platform_agent_definitions definition
JOIN ranked ON ranked.agent_id = definition.id AND ranked.position = 1
WHERE definition.status = 'ACTIVE' AND definition.archived_at IS NULL;

CREATE TABLE platform_agent_mcp_bindings (
    id                       UUID PRIMARY KEY,
    tenant_id                VARCHAR(64) NOT NULL,
    owner_id                 VARCHAR(36) NOT NULL,
    agent_id                 VARCHAR(36) NOT NULL,
    installation_id          UUID NOT NULL,
    connection_id            UUID NOT NULL,
    server_version_id        UUID NOT NULL,
    capability_snapshot_id   UUID NOT NULL,
    connection_revision      BIGINT NOT NULL,
    snapshot_sha256          VARCHAR(71) NOT NULL,
    allowed_tool_names       JSONB NOT NULL,
    binding_sha256           VARCHAR(71) NOT NULL,
    created_by               VARCHAR(36) NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_agent_mcp_connection UNIQUE (agent_id, connection_id),
    CONSTRAINT uk_agent_mcp_hash UNIQUE (agent_id, binding_sha256),
    CONSTRAINT fk_agent_mcp_current_configuration
        FOREIGN KEY (agent_id, tenant_id, owner_id)
        REFERENCES platform_agent_current_configurations(agent_id, tenant_id, owner_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_current_agent_mcp_installation
        FOREIGN KEY (installation_id, tenant_id)
        REFERENCES platform_mcp_installations(id, tenant_id),
    CONSTRAINT fk_current_agent_mcp_connection
        FOREIGN KEY (connection_id, installation_id, tenant_id)
        REFERENCES platform_mcp_connections(id, installation_id, tenant_id),
    CONSTRAINT fk_current_agent_mcp_server_version
        FOREIGN KEY (server_version_id) REFERENCES platform_mcp_server_versions(id),
    CONSTRAINT fk_current_agent_mcp_snapshot
        FOREIGN KEY (connection_id, capability_snapshot_id)
        REFERENCES platform_mcp_capability_snapshots(connection_id, id),
    CONSTRAINT ck_current_agent_mcp_binding CHECK (
        connection_revision > 0
        AND snapshot_sha256 ~ '^sha256:[0-9a-f]{64}$'
        AND binding_sha256 ~ '^sha256:[0-9a-f]{64}$'
        AND jsonb_typeof(allowed_tool_names) = 'array'
        AND jsonb_array_length(allowed_tool_names) BETWEEN 1 AND 100)
);

WITH latest AS (
    SELECT version.agent_id, version.id AS agent_version_id,
           row_number() OVER (
               PARTITION BY version.agent_id
               ORDER BY version.version_number DESC, version.created_at DESC, version.id DESC
           ) AS position
    FROM platform_agent_versions version
)
INSERT INTO platform_agent_mcp_bindings (
    id, tenant_id, owner_id, agent_id, installation_id, connection_id,
    server_version_id, capability_snapshot_id, connection_revision,
    snapshot_sha256, allowed_tool_names, binding_sha256, created_by, created_at
)
SELECT binding.id, binding.tenant_id, binding.owner_id, binding.agent_id,
       binding.installation_id, binding.connection_id, binding.server_version_id,
       binding.capability_snapshot_id, binding.connection_revision,
       binding.snapshot_sha256, binding.allowed_tool_names, binding.binding_sha256,
       binding.created_by, binding.created_at
FROM platform_agent_version_mcp_bindings binding
JOIN latest ON latest.agent_id = binding.agent_id
           AND latest.agent_version_id = binding.agent_version_id
           AND latest.position = 1
JOIN platform_agent_current_configurations current
  ON current.agent_id = binding.agent_id;

CREATE INDEX idx_agent_mcp_bindings_agent
    ON platform_agent_mcp_bindings(agent_id, created_at, id);

CREATE TABLE platform_agent_run_configuration_snapshots (
    id                       VARCHAR(36) PRIMARY KEY,
    run_id                   VARCHAR(36) NOT NULL UNIQUE,
    tenant_id                VARCHAR(64) NOT NULL,
    owner_id                 VARCHAR(36) NOT NULL,
    agent_id                 VARCHAR(36) NOT NULL,
    snapshot_state           VARCHAR(32) NOT NULL,
    agent_revision           BIGINT,
    config_hash              CHAR(64),
    system_prompt            TEXT,
    model_pool_id            UUID,
    model_provider_id        VARCHAR(100),
    model_id                 VARCHAR(160),
    temperature              DOUBLE PRECISION,
    max_context_tokens       INTEGER,
    max_output_tokens        INTEGER,
    max_turns                INTEGER,
    permission_mode          VARCHAR(20),
    memory_enabled           BOOLEAN,
    rag_enabled              BOOLEAN,
    network_enabled          BOOLEAN,
    knowledge_base_ids       JSONB,
    enabled_tool_ids         JSONB,
    skill_ids                JSONB,
    source_updated_by        VARCHAR(36),
    source_updated_at        TIMESTAMPTZ,
    captured_at              TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_agent_run_configuration_snapshot_run
        FOREIGN KEY (run_id) REFERENCES platform_agent_runs(id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_run_configuration_snapshot_agent
        FOREIGN KEY (agent_id) REFERENCES platform_agent_definitions(id) ON DELETE RESTRICT,
    CONSTRAINT fk_agent_run_configuration_snapshot_model_pool
        FOREIGN KEY (model_pool_id) REFERENCES platform_model_pools(id) ON DELETE RESTRICT,
    CONSTRAINT ck_agent_run_configuration_snapshot_state CHECK (
        snapshot_state IN ('SNAPSHOTTED', 'LEGACY_UNSNAPSHOTTED')),
    CONSTRAINT ck_agent_run_configuration_snapshot_payload CHECK (
        (snapshot_state = 'SNAPSHOTTED'
            AND agent_revision > 0
            AND config_hash ~ '^[0-9a-f]{64}$'
            AND temperature BETWEEN 0.0 AND 2.0
            AND max_context_tokens > 0
            AND max_output_tokens > 0
            AND max_turns > 0
            AND permission_mode IN ('private', 'auto', 'ask', 'deny')
            AND memory_enabled IS NOT NULL
            AND rag_enabled IS NOT NULL
            AND network_enabled IS NOT NULL
            AND jsonb_typeof(knowledge_base_ids) = 'array'
            AND jsonb_typeof(enabled_tool_ids) = 'array'
            AND jsonb_typeof(skill_ids) = 'array'
            AND source_updated_by IS NOT NULL
            AND source_updated_at IS NOT NULL)
        OR
        (snapshot_state = 'LEGACY_UNSNAPSHOTTED'
            AND agent_revision IS NULL AND config_hash IS NULL
            AND model_pool_id IS NULL AND model_provider_id IS NULL AND model_id IS NULL
            AND temperature IS NULL AND max_context_tokens IS NULL
            AND max_output_tokens IS NULL AND max_turns IS NULL
            AND permission_mode IS NULL AND memory_enabled IS NULL
            AND rag_enabled IS NULL AND network_enabled IS NULL
            AND knowledge_base_ids IS NULL AND enabled_tool_ids IS NULL
            AND skill_ids IS NULL AND source_updated_by IS NULL
            AND source_updated_at IS NULL)),
    CONSTRAINT ck_agent_run_configuration_snapshot_model_binding CHECK (
        snapshot_state = 'LEGACY_UNSNAPSHOTTED'
        OR (model_pool_id IS NOT NULL AND model_provider_id IS NULL AND model_id IS NULL)
        OR (model_pool_id IS NULL AND (
            (model_provider_id IS NULL AND model_id IS NULL)
            OR (model_provider_id IS NOT NULL AND model_id IS NOT NULL))))
);

INSERT INTO platform_agent_run_configuration_snapshots (
    id, run_id, tenant_id, owner_id, agent_id, snapshot_state,
    agent_revision, config_hash, system_prompt, model_pool_id,
    model_provider_id, model_id, temperature, max_context_tokens,
    max_output_tokens, max_turns, permission_mode, memory_enabled,
    rag_enabled, network_enabled, knowledge_base_ids, enabled_tool_ids,
    skill_ids, source_updated_by, source_updated_at, captured_at
)
SELECT run.id, run.id, definition.tenant_id, definition.owner_id, run.agent_id,
       CASE WHEN version.id IS NULL THEN 'LEGACY_UNSNAPSHOTTED' ELSE 'SNAPSHOTTED' END,
       version.source_agent_revision, version.config_hash, version.system_prompt,
       version.model_pool_id, version.model_provider_id, version.model_id,
       version.temperature, version.max_context_tokens, version.max_output_tokens,
       version.max_turns, version.permission_mode, version.memory_enabled,
       version.rag_enabled, version.network_enabled, version.knowledge_base_ids,
       version.enabled_tool_ids, version.skill_ids, version.created_by,
       version.created_at, run.created_at AT TIME ZONE 'UTC'
FROM platform_agent_runs run
JOIN platform_agent_definitions definition ON definition.id = run.agent_id
LEFT JOIN platform_agent_versions version
  ON version.id = run.agent_version_id AND version.agent_id = run.agent_id;

CREATE INDEX idx_agent_run_configuration_snapshots_agent
    ON platform_agent_run_configuration_snapshots(agent_id, captured_at DESC);

CREATE TABLE platform_agent_run_mcp_binding_snapshots (
    run_configuration_snapshot_id VARCHAR(36) NOT NULL,
    source_binding_id              UUID NOT NULL,
    installation_id               UUID NOT NULL,
    connection_id                 UUID NOT NULL,
    server_version_id             UUID NOT NULL,
    capability_snapshot_id        UUID NOT NULL,
    connection_revision           BIGINT NOT NULL,
    snapshot_sha256               VARCHAR(71) NOT NULL,
    allowed_tool_names            JSONB NOT NULL,
    binding_sha256                VARCHAR(71) NOT NULL,
    PRIMARY KEY (run_configuration_snapshot_id, source_binding_id),
    CONSTRAINT fk_agent_run_mcp_binding_snapshot
        FOREIGN KEY (run_configuration_snapshot_id)
        REFERENCES platform_agent_run_configuration_snapshots(id)
        ON DELETE CASCADE,
    CONSTRAINT ck_agent_run_mcp_binding_snapshot CHECK (
        connection_revision > 0
        AND snapshot_sha256 ~ '^sha256:[0-9a-f]{64}$'
        AND binding_sha256 ~ '^sha256:[0-9a-f]{64}$'
        AND jsonb_typeof(allowed_tool_names) = 'array'
        AND jsonb_array_length(allowed_tool_names) BETWEEN 1 AND 100)
);

INSERT INTO platform_agent_run_mcp_binding_snapshots (
    run_configuration_snapshot_id, source_binding_id, installation_id,
    connection_id, server_version_id, capability_snapshot_id,
    connection_revision, snapshot_sha256, allowed_tool_names, binding_sha256
)
SELECT snapshot.id, binding.id, binding.installation_id, binding.connection_id,
       binding.server_version_id, binding.capability_snapshot_id,
       binding.connection_revision, binding.snapshot_sha256,
       binding.allowed_tool_names, binding.binding_sha256
FROM platform_agent_run_configuration_snapshots snapshot
JOIN platform_agent_runs run ON run.id = snapshot.run_id
JOIN platform_agent_version_mcp_bindings binding
  ON binding.agent_version_id = run.agent_version_id
WHERE snapshot.snapshot_state = 'SNAPSHOTTED';

COMMENT ON TABLE platform_agent_current_configurations IS
    'M74 Agent-owned one current mutable configuration; no product version lifecycle';
COMMENT ON TABLE platform_agent_run_configuration_snapshots IS
    'Runtime-owned immutable secret-free effective Agent configuration captured once per Run';
COMMENT ON COLUMN platform_agent_run_configuration_snapshots.snapshot_state IS
    'LEGACY_UNSNAPSHOTTED is honest evidence for historical Runs that never pinned AgentVersion';
