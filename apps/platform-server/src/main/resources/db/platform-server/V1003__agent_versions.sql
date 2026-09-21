ALTER TABLE platform_agent_configurations
    ADD COLUMN IF NOT EXISTS current_agent_version_id UUID,
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;

ALTER TABLE platform_agent_configurations
    DROP CONSTRAINT IF EXISTS uk_platform_agent_config_tenant_name;

CREATE UNIQUE INDEX IF NOT EXISTS uk_platform_agent_config_active_tenant_name
    ON platform_agent_configurations(tenant_id, name)
    WHERE archived_at IS NULL;

CREATE TABLE platform_agent_versions (
    id                       UUID PRIMARY KEY,
    agent_configuration_id   VARCHAR(36) NOT NULL
        REFERENCES platform_agent_configurations(id) ON DELETE RESTRICT,
    version_number           INTEGER NOT NULL,
    status                   VARCHAR(24) NOT NULL,
    config_hash              CHAR(64) NOT NULL,
    system_prompt            TEXT,
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
    knowledge_base_ids       JSONB NOT NULL DEFAULT '[]'::jsonb,
    enabled_tool_ids         JSONB NOT NULL DEFAULT '[]'::jsonb,
    skill_ids                JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_config_version    BIGINT NOT NULL,
    created_by               VARCHAR(36) NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL,
    deprecated_at            TIMESTAMPTZ,
    CONSTRAINT uk_platform_agent_version_number
        UNIQUE (agent_configuration_id, version_number),
    CONSTRAINT ck_platform_agent_version_number_positive
        CHECK (version_number > 0),
    CONSTRAINT ck_platform_agent_version_status
        CHECK (status IN ('PUBLISHED', 'DEPRECATED')),
    CONSTRAINT ck_platform_agent_version_hash
        CHECK (config_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_platform_agent_version_context_tokens_positive
        CHECK (max_context_tokens > 0),
    CONSTRAINT ck_platform_agent_version_output_tokens_positive
        CHECK (max_output_tokens > 0),
    CONSTRAINT ck_platform_agent_version_max_turns_positive
        CHECK (max_turns > 0),
    CONSTRAINT ck_platform_agent_version_knowledge_array
        CHECK (jsonb_typeof(knowledge_base_ids) = 'array'),
    CONSTRAINT ck_platform_agent_version_tool_array
        CHECK (jsonb_typeof(enabled_tool_ids) = 'array'),
    CONSTRAINT ck_platform_agent_version_skill_array
        CHECK (jsonb_typeof(skill_ids) = 'array')
);

CREATE INDEX idx_platform_agent_versions_configuration
    ON platform_agent_versions(agent_configuration_id, version_number DESC);

WITH normalized AS (
    SELECT configuration.*,
           (
               SELECT COALESCE(jsonb_agg(item.value ORDER BY item.value), '[]'::jsonb)
               FROM (
                   SELECT DISTINCT binding.knowledge_base_id AS value
                   FROM platform_agent_knowledge_bindings binding
                   WHERE binding.agent_id = configuration.id
               ) item
           ) AS normalized_knowledge_ids,
           (
               SELECT COALESCE(jsonb_agg(item.value ORDER BY item.value), '[]'::jsonb)
               FROM (
                   SELECT DISTINCT value
                   FROM jsonb_array_elements_text(
                       CASE
                           WHEN configuration.enabled_tool_ids IS NULL
                             OR btrim(configuration.enabled_tool_ids) = ''
                           THEN '[]'::jsonb
                           ELSE configuration.enabled_tool_ids::jsonb
                       END
                   ) AS values(value)
               ) item
           ) AS normalized_tool_ids,
           (
               SELECT COALESCE(jsonb_agg(item.value ORDER BY item.value), '[]'::jsonb)
               FROM (
                   SELECT DISTINCT value
                   FROM jsonb_array_elements_text(
                       CASE
                           WHEN configuration.skill_ids IS NULL
                             OR btrim(configuration.skill_ids) = ''
                           THEN '[]'::jsonb
                           ELSE configuration.skill_ids::jsonb
                       END
                   ) AS values(value)
               ) item
           ) AS normalized_skill_ids
    FROM platform_agent_configurations configuration
), canonical AS (
    SELECT normalized.*,
           md5('agent-version-v1:' || normalized.id) AS version_seed,
           concat_ws(E'\n',
               'systemPrompt:' || to_jsonb(COALESCE(normalized.system_prompt, ''))::text,
               'modelProviderId:' || CASE
                   WHEN normalized.model_provider_id IS NULL THEN 'null'
                   ELSE to_jsonb(normalized.model_provider_id)::text END,
               'modelId:' || CASE
                   WHEN normalized.model_id IS NULL THEN 'null'
                   ELSE to_jsonb(normalized.model_id)::text END,
               'temperature:' || normalized.temperature::text,
               'maxContextTokens:32768',
               'maxOutputTokens:' || normalized.max_tokens::text,
               'maxTurns:' || normalized.max_turns::text,
               'permissionMode:' || to_jsonb(normalized.permission_mode)::text,
               'memoryEnabled:' || normalized.memory_enabled::text,
               'ragEnabled:' || normalized.rag_enabled::text,
               'networkEnabled:' || normalized.network_enabled::text,
               'knowledgeBaseIds:' || normalized.normalized_knowledge_ids::text,
               'enabledToolIds:' || normalized.normalized_tool_ids::text,
               'skillIds:' || normalized.normalized_skill_ids::text
           ) AS canonical_config
    FROM normalized
), backfill AS (
    SELECT (
               substr(version_seed, 1, 8) || '-' ||
               substr(version_seed, 9, 4) || '-' ||
               substr(version_seed, 13, 4) || '-' ||
               substr(version_seed, 17, 4) || '-' ||
               substr(version_seed, 21, 12)
           )::uuid AS version_id,
           canonical.*
    FROM canonical
)
INSERT INTO platform_agent_versions (
    id, agent_configuration_id, version_number, status, config_hash,
    system_prompt, model_provider_id, model_id, temperature,
    max_context_tokens, max_output_tokens, max_turns, permission_mode,
    memory_enabled, rag_enabled, network_enabled,
    knowledge_base_ids, enabled_tool_ids, skill_ids,
    source_config_version, created_by, created_at, deprecated_at
)
SELECT version_id,
       id,
       1,
       'PUBLISHED',
       encode(sha256(convert_to(canonical_config, 'UTF8')), 'hex'),
       system_prompt,
       model_provider_id,
       model_id,
       temperature,
       32768,
       max_tokens,
       max_turns,
       permission_mode,
       memory_enabled,
       rag_enabled,
       network_enabled,
       normalized_knowledge_ids,
       normalized_tool_ids,
       normalized_skill_ids,
       config_version,
       owner_id,
       COALESCE(updated_at, created_at, CURRENT_TIMESTAMP),
       NULL
FROM backfill
ON CONFLICT (agent_configuration_id, version_number) DO NOTHING;

UPDATE platform_agent_configurations configuration
SET current_agent_version_id = version.id
FROM platform_agent_versions version
WHERE version.agent_configuration_id = configuration.id
  AND version.version_number = 1
  AND configuration.current_agent_version_id IS NULL;

ALTER TABLE platform_agent_configurations
    ADD CONSTRAINT fk_platform_agent_config_current_version
        FOREIGN KEY (current_agent_version_id)
        REFERENCES platform_agent_versions(id)
        ON DELETE RESTRICT;
