CREATE TABLE IF NOT EXISTS platform_tenant_memberships (
    tenant_id   VARCHAR(36) NOT NULL REFERENCES platform_tenants(id) ON DELETE CASCADE,
    user_id     VARCHAR(36) NOT NULL REFERENCES platform_users(id) ON DELETE CASCADE,
    tenant_role VARCHAR(20) NOT NULL,
    status      VARCHAR(20) NOT NULL,
    joined_at   TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    PRIMARY KEY (tenant_id, user_id),
    CONSTRAINT ck_platform_tenant_membership_role
        CHECK (tenant_role IN ('OWNER', 'ADMIN', 'MEMBER', 'VIEWER')),
    CONSTRAINT ck_platform_tenant_membership_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

CREATE INDEX IF NOT EXISTS idx_platform_tenant_memberships_user
    ON platform_tenant_memberships(user_id, status, joined_at);

-- Existing platform users predate explicit membership state. Their current
-- tenant_id is the authoritative personal-tenant ownership reference.
INSERT INTO platform_tenant_memberships (
    tenant_id, user_id, tenant_role, status, joined_at, updated_at
)
SELECT tenant_id, id, 'OWNER', 'ACTIVE', created_at, updated_at
FROM platform_users
ON CONFLICT (tenant_id, user_id) DO NOTHING;

CREATE TABLE IF NOT EXISTS platform_refresh_tokens (
    token_hash       CHAR(64) PRIMARY KEY,
    user_id          VARCHAR(36) NOT NULL REFERENCES platform_users(id) ON DELETE CASCADE,
    tenant_id        VARCHAR(36) NOT NULL REFERENCES platform_tenants(id) ON DELETE CASCADE,
    tenant_role      VARCHAR(20) NOT NULL,
    expires_at       TIMESTAMP NOT NULL,
    revoked_at       TIMESTAMP,
    replaced_by_hash CHAR(64),
    created_at       TIMESTAMP NOT NULL,
    CONSTRAINT ck_platform_refresh_token_tenant_role
        CHECK (tenant_role IN ('OWNER', 'ADMIN', 'MEMBER', 'VIEWER'))
);

CREATE INDEX IF NOT EXISTS idx_platform_refresh_tokens_user_active
    ON platform_refresh_tokens(user_id, expires_at)
    WHERE revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_platform_refresh_tokens_expiry
    ON platform_refresh_tokens(expires_at);

CREATE TABLE IF NOT EXISTS platform_access_token_revocations (
    token_hash CHAR(64) PRIMARY KEY,
    user_id    VARCHAR(36) NOT NULL REFERENCES platform_users(id) ON DELETE CASCADE,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_platform_access_token_revocations_expiry
    ON platform_access_token_revocations(expires_at);
