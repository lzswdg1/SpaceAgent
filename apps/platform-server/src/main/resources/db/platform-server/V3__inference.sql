CREATE TABLE IF NOT EXISTS platform_model_providers (
    id                    VARCHAR(36) PRIMARY KEY,
    tenant_id             VARCHAR(36) NOT NULL,
    owner_id              VARCHAR(36) NOT NULL,
    name                  VARCHAR(120) NOT NULL,
    provider_type         VARCHAR(32) NOT NULL,
    base_url              VARCHAR(500) NOT NULL,
    api_key_ciphertext    TEXT NOT NULL,
    auth_type             VARCHAR(32) NOT NULL,
    enabled               BOOLEAN NOT NULL,
    is_default            BOOLEAN NOT NULL,
    created_at            TIMESTAMP NOT NULL,
    updated_at            TIMESTAMP NOT NULL,
    CONSTRAINT uk_platform_model_provider_tenant_name UNIQUE (tenant_id, name)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_platform_model_provider_tenant_default
    ON platform_model_providers(tenant_id)
    WHERE is_default = TRUE;

CREATE INDEX IF NOT EXISTS idx_platform_model_provider_tenant_enabled
    ON platform_model_providers(tenant_id, enabled);

CREATE TABLE IF NOT EXISTS platform_provider_models (
    id                    VARCHAR(36) PRIMARY KEY,
    provider_id           VARCHAR(36) NOT NULL REFERENCES platform_model_providers(id) ON DELETE CASCADE,
    model_id              VARCHAR(160) NOT NULL,
    display_name          VARCHAR(160) NOT NULL,
    max_context_tokens    INTEGER NOT NULL,
    is_default            BOOLEAN NOT NULL,
    created_at            TIMESTAMP NOT NULL,
    CONSTRAINT uk_platform_provider_model UNIQUE (provider_id, model_id)
);

CREATE INDEX IF NOT EXISTS idx_platform_provider_model_provider
    ON platform_provider_models(provider_id);
