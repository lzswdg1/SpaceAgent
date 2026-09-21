DO $agent_version_mcp_bindings$
BEGIN
 IF to_regclass('public.platform_agent_versions') IS NULL OR to_regclass('public.platform_mcp_capability_snapshots') IS NULL THEN RETURN; END IF;
 ALTER TABLE platform_agent_definitions ADD CONSTRAINT uk_agent_scope_mcp_binding UNIQUE(id,tenant_id,owner_id);
 ALTER TABLE platform_agent_versions ADD CONSTRAINT uk_agent_version_scope_mcp_binding UNIQUE(agent_id,id);
 ALTER TABLE platform_mcp_installations ADD CONSTRAINT uk_mcp_installation_tenant_binding UNIQUE(id,tenant_id);
 ALTER TABLE platform_mcp_connections ADD CONSTRAINT uk_mcp_connection_installation_tenant_binding UNIQUE(id,installation_id,tenant_id);
 CREATE TABLE platform_agent_version_mcp_bindings(
  id UUID PRIMARY KEY,tenant_id VARCHAR(36) NOT NULL,owner_id VARCHAR(36) NOT NULL,agent_id VARCHAR(36) NOT NULL,
  agent_version_id UUID NOT NULL,installation_id UUID NOT NULL,connection_id UUID NOT NULL,server_version_id UUID NOT NULL,
  capability_snapshot_id UUID NOT NULL,connection_revision BIGINT NOT NULL,snapshot_sha256 VARCHAR(71) NOT NULL,
  allowed_tool_names JSONB NOT NULL,binding_sha256 VARCHAR(71) NOT NULL,created_by VARCHAR(36) NOT NULL,created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uk_agent_version_mcp_connection UNIQUE(agent_version_id,connection_id),
  CONSTRAINT uk_agent_version_mcp_hash UNIQUE(agent_version_id,binding_sha256),
  CONSTRAINT fk_agent_mcp_definition FOREIGN KEY(agent_id,tenant_id,owner_id) REFERENCES platform_agent_definitions(id,tenant_id,owner_id),
  CONSTRAINT fk_agent_mcp_version FOREIGN KEY(agent_id,agent_version_id) REFERENCES platform_agent_versions(agent_id,id),
  CONSTRAINT fk_agent_mcp_installation FOREIGN KEY(installation_id,tenant_id) REFERENCES platform_mcp_installations(id,tenant_id),
  CONSTRAINT fk_agent_mcp_connection FOREIGN KEY(connection_id,installation_id,tenant_id) REFERENCES platform_mcp_connections(id,installation_id,tenant_id),
  CONSTRAINT fk_agent_mcp_server_version FOREIGN KEY(server_version_id) REFERENCES platform_mcp_server_versions(id),
  CONSTRAINT fk_agent_mcp_snapshot FOREIGN KEY(connection_id,capability_snapshot_id) REFERENCES platform_mcp_capability_snapshots(connection_id,id),
  CONSTRAINT ck_agent_mcp_binding CHECK(connection_revision>0 AND snapshot_sha256~'^sha256:[0-9a-f]{64}$' AND binding_sha256~'^sha256:[0-9a-f]{64}$' AND jsonb_typeof(allowed_tool_names)='array' AND jsonb_array_length(allowed_tool_names) BETWEEN 1 AND 100));
 CREATE INDEX idx_agent_version_mcp_bindings_version ON platform_agent_version_mcp_bindings(agent_version_id,created_at,id);
END $agent_version_mcp_bindings$;
