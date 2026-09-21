ALTER TABLE platform_users ADD COLUMN access_version BIGINT NOT NULL DEFAULT 0 CHECK (access_version >= 0);
ALTER TABLE platform_refresh_tokens ADD COLUMN access_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE platform_refresh_tokens ADD COLUMN session_id UUID NOT NULL DEFAULT gen_random_uuid();
CREATE INDEX idx_platform_refresh_session ON platform_refresh_tokens(session_id,user_id) WHERE revoked_at IS NULL;

CREATE TABLE platform_presence_leases (
    session_id UUID PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES platform_users(id) ON DELETE CASCADE,
    tenant_id VARCHAR(64) NOT NULL REFERENCES platform_tenants(id),
    access_version BIGINT NOT NULL,
    access_hash VARCHAR(64) NOT NULL,
    client_type VARCHAR(16) NOT NULL CHECK (client_type IN ('WEB','CLI','API','UNKNOWN')),
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_heartbeat_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_platform_presence_live ON platform_presence_leases(expires_at,user_id,tenant_id);
CREATE TABLE platform_user_password_reset_tokens (
    id UUID PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL REFERENCES platform_users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    access_version BIGINT NOT NULL CHECK (access_version >= 0),
    expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID NOT NULL
);
CREATE INDEX idx_platform_password_reset_user ON platform_user_password_reset_tokens(user_id,expires_at);
