-- Preserve the unrelated historical Definition rows without leaving them as an active owner.
ALTER TABLE platform_agent_definitions
    RENAME TO platform_legacy_agent_definitions;

ALTER INDEX IF EXISTS idx_platform_agent_definitions_owner
    RENAME TO idx_platform_legacy_agent_definitions_owner;

-- The product-facing AgentConfiguration ID is already the public and relational agentId.
ALTER TABLE platform_agent_configurations
    RENAME TO platform_agent_definitions;

ALTER INDEX IF EXISTS idx_platform_agent_config_owner
    RENAME TO idx_platform_agent_definitions_owner;

ALTER INDEX IF EXISTS uk_platform_agent_config_active_tenant_name
    RENAME TO uk_platform_agent_definition_active_tenant_name;

ALTER TABLE platform_agent_definitions
    RENAME CONSTRAINT fk_platform_agent_config_current_version
        TO fk_platform_agent_definition_current_version;

ALTER TABLE platform_agent_definitions
    ADD COLUMN status VARCHAR(24) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE platform_agent_definitions
    RENAME COLUMN config_version TO revision;

UPDATE platform_agent_definitions
SET status = 'ARCHIVED'
WHERE archived_at IS NOT NULL;

ALTER TABLE platform_agent_definitions
    ADD CONSTRAINT ck_platform_agent_definition_status
        CHECK (status IN ('ACTIVE', 'ARCHIVED'));

-- Runtime-affecting state now belongs only to immutable AgentVersion rows.
ALTER TABLE platform_agent_definitions
    DROP COLUMN system_prompt,
    DROP COLUMN model_provider_id,
    DROP COLUMN model_id,
    DROP COLUMN temperature,
    DROP COLUMN max_tokens,
    DROP COLUMN max_turns,
    DROP COLUMN permission_mode,
    DROP COLUMN memory_enabled,
    DROP COLUMN rag_enabled,
    DROP COLUMN network_enabled,
    DROP COLUMN knowledge_base_ids,
    DROP COLUMN enabled_tool_ids,
    DROP COLUMN skill_ids;

ALTER TABLE platform_agent_definitions
    ADD CONSTRAINT ck_platform_agent_definition_revision_positive
        CHECK (revision > 0);

ALTER TABLE platform_agent_versions
    DROP CONSTRAINT platform_agent_versions_agent_configuration_id_fkey;

ALTER TABLE platform_agent_versions
    RENAME COLUMN agent_configuration_id TO agent_id;

ALTER TABLE platform_agent_versions
    RENAME COLUMN source_config_version TO source_agent_revision;

ALTER TABLE platform_agent_versions
    RENAME CONSTRAINT uk_platform_agent_version_number
        TO uk_platform_agent_version_agent_number;

ALTER INDEX IF EXISTS idx_platform_agent_versions_configuration
    RENAME TO idx_platform_agent_versions_agent;

ALTER TABLE platform_agent_versions
    ADD CONSTRAINT fk_platform_agent_version_agent
        FOREIGN KEY (agent_id)
        REFERENCES platform_agent_definitions(id)
        ON DELETE RESTRICT;

ALTER TABLE platform_agent_knowledge_bindings
    DROP CONSTRAINT platform_agent_knowledge_bindings_agent_id_fkey;

ALTER TABLE platform_agent_knowledge_bindings
    ADD CONSTRAINT fk_platform_agent_knowledge_binding_agent
        FOREIGN KEY (agent_id)
        REFERENCES platform_agent_definitions(id)
        ON DELETE RESTRICT;

ALTER TABLE platform_agent_api_keys
    DROP CONSTRAINT platform_agent_api_keys_agent_id_fkey;

ALTER TABLE platform_agent_api_keys
    ADD CONSTRAINT fk_platform_agent_api_key_agent
        FOREIGN KEY (agent_id)
        REFERENCES platform_agent_definitions(id)
        ON DELETE RESTRICT;

ALTER TABLE platform_conversations
    ADD CONSTRAINT fk_platform_conversation_agent
        FOREIGN KEY (agent_id)
        REFERENCES platform_agent_definitions(id)
        ON DELETE RESTRICT
        NOT VALID;

COMMENT ON TABLE platform_agent_definitions IS
    'Canonical product Agent identity and lifecycle; runtime configuration is owned by AgentVersion';

COMMENT ON TABLE platform_legacy_agent_definitions IS
    'Unrelated pre-convergence Definition rows retained as reference-only legacy data';
