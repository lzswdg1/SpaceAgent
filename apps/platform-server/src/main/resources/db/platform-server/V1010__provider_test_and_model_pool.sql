-- Provider connectivity eligibility and durable ModelPool foundation.
DO $provider_test_model_pool$
BEGIN
    IF to_regclass('public.platform_model_providers') IS NULL
            OR to_regclass('public.platform_provider_models') IS NULL
            OR to_regclass('public.platform_tenants') IS NULL
            OR to_regclass('public.platform_users') IS NULL THEN
        RETURN;
    END IF;

    ALTER TABLE platform_model_providers
        ADD COLUMN connection_status VARCHAR(32) NOT NULL DEFAULT 'UNTESTED',
        ADD COLUMN last_tested_at TIMESTAMPTZ,
        ADD COLUMN last_test_latency_ms INTEGER,
        ADD COLUMN last_test_error_code VARCHAR(64);

    UPDATE platform_model_providers
    SET connection_status = 'DISABLED'
    WHERE enabled = FALSE;

    ALTER TABLE platform_model_providers
        ADD CONSTRAINT ck_platform_model_provider_connection_status
            CHECK (connection_status IN ('UNTESTED', 'ACTIVE', 'UNHEALTHY', 'DISABLED')),
        ADD CONSTRAINT ck_platform_model_provider_test_latency
            CHECK (last_test_latency_ms IS NULL OR last_test_latency_ms >= 0);

    ALTER TABLE platform_provider_models
        ADD CONSTRAINT uk_platform_provider_model_provider_id_id
            UNIQUE (provider_id, id);

    CREATE TABLE platform_model_pools (
        id                UUID PRIMARY KEY,
        tenant_id         VARCHAR(36) NOT NULL,
        owner_id          VARCHAR(36) NOT NULL,
        name              VARCHAR(120) NOT NULL,
        visibility        VARCHAR(32) NOT NULL,
        routing_strategy  VARCHAR(32) NOT NULL,
        fallback_enabled  BOOLEAN NOT NULL,
        status            VARCHAR(32) NOT NULL,
        created_at        TIMESTAMPTZ NOT NULL,
        updated_at        TIMESTAMPTZ NOT NULL,
        CONSTRAINT uk_platform_model_pool_tenant_name UNIQUE (tenant_id, name),
        CONSTRAINT ck_platform_model_pool_visibility
            CHECK (visibility IN ('PRIVATE', 'ORGANIZATION')),
        CONSTRAINT ck_platform_model_pool_routing
            CHECK (routing_strategy IN ('PRIORITY')),
        CONSTRAINT ck_platform_model_pool_status
            CHECK (status IN ('DRAFT', 'ACTIVE', 'DISABLED')),
        CONSTRAINT fk_platform_model_pool_tenant
            FOREIGN KEY (tenant_id) REFERENCES platform_tenants(id),
        CONSTRAINT fk_platform_model_pool_owner
            FOREIGN KEY (owner_id) REFERENCES platform_users(id)
    );

    CREATE INDEX idx_platform_model_pool_tenant_status
        ON platform_model_pools(tenant_id, status, created_at);

    CREATE TABLE platform_model_pool_members (
        id                 UUID PRIMARY KEY,
        pool_id            UUID NOT NULL,
        provider_id        VARCHAR(36) NOT NULL,
        provider_model_id  VARCHAR(36) NOT NULL,
        priority           INTEGER NOT NULL,
        weight             INTEGER NOT NULL,
        enabled            BOOLEAN NOT NULL,
        created_at         TIMESTAMPTZ NOT NULL,
        updated_at         TIMESTAMPTZ NOT NULL,
        CONSTRAINT uk_platform_model_pool_member UNIQUE (pool_id, provider_model_id),
        CONSTRAINT ck_platform_model_pool_member_priority
            CHECK (priority BETWEEN 0 AND 10000),
        CONSTRAINT ck_platform_model_pool_member_weight
            CHECK (weight BETWEEN 1 AND 1000),
        CONSTRAINT fk_platform_model_pool_member_pool
            FOREIGN KEY (pool_id) REFERENCES platform_model_pools(id) ON DELETE CASCADE,
        CONSTRAINT fk_platform_model_pool_member_provider
            FOREIGN KEY (provider_id) REFERENCES platform_model_providers(id),
        CONSTRAINT fk_platform_model_pool_member_model
            FOREIGN KEY (provider_id, provider_model_id)
            REFERENCES platform_provider_models(provider_id, id)
    );

    CREATE INDEX idx_platform_model_pool_member_resolution
        ON platform_model_pool_members(pool_id, enabled, priority, id);

    COMMENT ON TABLE platform_model_pools IS
        'Stable Organization/user-owned model routing entry point; Agent binding is a later milestone';
END
$provider_test_model_pool$;
