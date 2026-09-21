CREATE TABLE IF NOT EXISTS platform_tenants (
    id          VARCHAR(36) PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    slug        VARCHAR(63)  NOT NULL,
    status      VARCHAR(24)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    CONSTRAINT uk_platform_tenants_slug UNIQUE (slug)
);

CREATE TABLE IF NOT EXISTS platform_users (
    id            VARCHAR(36) PRIMARY KEY,
    tenant_id     VARCHAR(36) NOT NULL REFERENCES platform_tenants(id),
    external_id   VARCHAR(255) NOT NULL,
    display_name  VARCHAR(120) NOT NULL,
    created_at    TIMESTAMP NOT NULL,
    updated_at    TIMESTAMP NOT NULL,
    CONSTRAINT uk_platform_users_tenant_external UNIQUE (tenant_id, external_id)
);

CREATE INDEX IF NOT EXISTS idx_platform_users_tenant
    ON platform_users(tenant_id);

CREATE TABLE IF NOT EXISTS platform_user_profiles (
    user_id          VARCHAR(36) PRIMARY KEY REFERENCES platform_users(id),
    preferred_tone   VARCHAR(32) NOT NULL,
    timezone         VARCHAR(64) NOT NULL,
    summary          VARCHAR(1000) NOT NULL,
    created_at       TIMESTAMP NOT NULL,
    updated_at       TIMESTAMP NOT NULL
);
