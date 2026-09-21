-- Normalize the existing Agent -> Knowledge references into the binding table
-- already introduced by V2. No Knowledge data is copied into the Agent module.
INSERT INTO platform_agent_knowledge_bindings (agent_id, knowledge_base_id)
SELECT configuration.id, binding.knowledge_id
FROM platform_agent_configurations configuration
CROSS JOIN LATERAL jsonb_array_elements_text(
    CASE
        WHEN configuration.knowledge_base_ids IS NULL
          OR btrim(configuration.knowledge_base_ids) = ''
        THEN '[]'::jsonb
        ELSE configuration.knowledge_base_ids::jsonb
    END
) AS binding(knowledge_id)
ON CONFLICT (agent_id, knowledge_base_id) DO NOTHING;

CREATE INDEX IF NOT EXISTS idx_platform_agent_knowledge_binding_knowledge
    ON platform_agent_knowledge_bindings(knowledge_base_id, agent_id);

CREATE TABLE IF NOT EXISTS platform_agent_api_keys (
    id           VARCHAR(36) PRIMARY KEY,
    agent_id     VARCHAR(36) NOT NULL
        REFERENCES platform_agent_configurations(id) ON DELETE CASCADE,
    name         VARCHAR(128) NOT NULL,
    key_hash     CHAR(64) NOT NULL,
    key_prefix   VARCHAR(12) NOT NULL,
    scopes       VARCHAR(256) NOT NULL DEFAULT 'CHAT',
    enabled      BOOLEAN NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP NOT NULL,
    last_used_at TIMESTAMP,
    expires_at   TIMESTAMP,
    revoked_at   TIMESTAMP,
    CONSTRAINT uk_platform_agent_api_key_name UNIQUE (agent_id, name),
    CONSTRAINT uk_platform_agent_api_key_hash UNIQUE (key_hash)
);

CREATE INDEX IF NOT EXISTS idx_platform_agent_api_keys_agent
    ON platform_agent_api_keys(agent_id, created_at DESC);
