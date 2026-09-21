DO $github_mcp_oauth$ BEGIN
 IF to_regclass('public.platform_mcp_connections') IS NULL THEN RETURN;END IF;
 CREATE TABLE platform_mcp_oauth_states(
  id UUID PRIMARY KEY,connection_id UUID NOT NULL REFERENCES platform_mcp_connections(id) ON DELETE CASCADE,
  tenant_id VARCHAR(36) NOT NULL REFERENCES platform_tenants(id),user_id VARCHAR(36) NOT NULL REFERENCES platform_users(id),
  state_hash CHAR(64) NOT NULL UNIQUE,encrypted_provider_session TEXT NOT NULL,expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,consumed_at TIMESTAMPTZ);
 CREATE INDEX idx_mcp_oauth_expiry ON platform_mcp_oauth_states(expires_at) WHERE consumed_at IS NULL;
END $github_mcp_oauth$;
