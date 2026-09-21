CREATE TABLE IF NOT EXISTS platform_agent_definitions (
    id             VARCHAR(36) PRIMARY KEY,
    owner_id       VARCHAR(36) NOT NULL,
    name           VARCHAR(120) NOT NULL,
    version        VARCHAR(40)  NOT NULL,
    configuration  TEXT         NOT NULL,
    status         VARCHAR(24)  NOT NULL,
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_platform_agent_definitions_owner
    ON platform_agent_definitions(owner_id);

CREATE TABLE IF NOT EXISTS platform_agent_configurations (
    id                  VARCHAR(36) PRIMARY KEY,
    owner_id            VARCHAR(36) NOT NULL,
    tenant_id           VARCHAR(36) NOT NULL,
    name                VARCHAR(120) NOT NULL,
    description         TEXT,
    system_prompt       TEXT,
    model_provider_id   VARCHAR(100),
    model_id            VARCHAR(160),
    temperature         DOUBLE PRECISION NOT NULL,
    max_tokens          INTEGER NOT NULL,
    max_turns           INTEGER NOT NULL,
    permission_mode     VARCHAR(20) NOT NULL,
    memory_enabled      BOOLEAN NOT NULL,
    rag_enabled         BOOLEAN NOT NULL,
    network_enabled     BOOLEAN NOT NULL,
    knowledge_base_ids  TEXT NOT NULL,
    enabled_tool_ids    TEXT NOT NULL,
    skill_ids           TEXT NOT NULL,
    config_version      BIGINT NOT NULL,
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL,
    CONSTRAINT uk_platform_agent_config_tenant_name UNIQUE (tenant_id, name)
);

CREATE INDEX IF NOT EXISTS idx_platform_agent_config_owner
    ON platform_agent_configurations(owner_id);

CREATE TABLE IF NOT EXISTS platform_agent_knowledge_bindings (
    agent_id           VARCHAR(36) NOT NULL REFERENCES platform_agent_configurations(id) ON DELETE CASCADE,
    knowledge_base_id  VARCHAR(36) NOT NULL,
    PRIMARY KEY (agent_id, knowledge_base_id)
);
