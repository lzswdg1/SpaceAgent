DO $mcp_marketplace$
BEGIN
 IF to_regclass('public.platform_tenants') IS NULL OR to_regclass('public.platform_users') IS NULL THEN RETURN; END IF;
 CREATE TABLE platform_mcp_marketplace_entries(
  id UUID PRIMARY KEY,slug VARCHAR(80) NOT NULL UNIQUE,name VARCHAR(120) NOT NULL,description TEXT NOT NULL,
  transport VARCHAR(32) NOT NULL,auth_type VARCHAR(24) NOT NULL,default_endpoint TEXT,manifest_json JSONB NOT NULL,
  enabled BOOLEAN NOT NULL,created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_mcp_entry_transport CHECK(transport IN('STREAMABLE_HTTP')),
  CONSTRAINT ck_mcp_entry_auth CHECK(auth_type IN('NONE','OAUTH2','BEARER','CUSTOM')));
 CREATE TABLE platform_mcp_installations(
  id UUID PRIMARY KEY,entry_id UUID NOT NULL REFERENCES platform_mcp_marketplace_entries(id),
  tenant_id VARCHAR(36) NOT NULL REFERENCES platform_tenants(id),subject_id VARCHAR(36) NOT NULL,
  created_by VARCHAR(36) NOT NULL REFERENCES platform_users(id),scope VARCHAR(24) NOT NULL,
  display_name VARCHAR(120) NOT NULL,state VARCHAR(24) NOT NULL,created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uk_mcp_install_subject UNIQUE(tenant_id,entry_id,scope,subject_id),
  CONSTRAINT ck_mcp_install_scope CHECK(scope IN('USER','ORGANIZATION')),
  CONSTRAINT ck_mcp_install_state CHECK(state IN('INSTALLED','DISABLED','REMOVED')));
 CREATE INDEX idx_mcp_install_visible ON platform_mcp_installations(tenant_id,subject_id,state);
 CREATE TABLE platform_mcp_connections(
  id UUID PRIMARY KEY,installation_id UUID NOT NULL UNIQUE REFERENCES platform_mcp_installations(id) ON DELETE CASCADE,
  tenant_id VARCHAR(36) NOT NULL REFERENCES platform_tenants(id),managed_by VARCHAR(36) NOT NULL REFERENCES platform_users(id),
  endpoint_url TEXT NOT NULL,encrypted_auth_json TEXT NOT NULL,auth_type VARCHAR(24) NOT NULL,state VARCHAR(24) NOT NULL,
  external_account_id VARCHAR(160),external_account_name VARCHAR(160),revision BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL,revoked_at TIMESTAMPTZ,
  CONSTRAINT ck_mcp_connection_auth CHECK(auth_type IN('NONE','OAUTH2','BEARER','CUSTOM')),
  CONSTRAINT ck_mcp_connection_state CHECK(state IN('PENDING_AUTH','ACTIVE','ERROR','REVOKED')),
  CONSTRAINT ck_mcp_connection_revision CHECK(revision>0),
  CONSTRAINT ck_mcp_connection_revoke CHECK((state='REVOKED')=(revoked_at IS NOT NULL)));
 INSERT INTO platform_mcp_marketplace_entries VALUES
 ('26000000-0000-4000-8000-000000000001','github','GitHub','GitHub repositories, issues, pull requests and code search through MCP','STREAMABLE_HTTP','OAUTH2',NULL,
  '{"capabilities":["repositories","issues","pull_requests","code_search"],"accountLogin":true,"publicUrlDiscovery":true}'::jsonb,TRUE,clock_timestamp(),clock_timestamp()),
 ('26000000-0000-4000-8000-000000000002','custom-streamable-http','Custom MCP','Connect an allowlisted Streamable HTTP MCP server','STREAMABLE_HTTP','CUSTOM',NULL,
  '{"capabilities":["custom_tools"],"accountLogin":false,"publicUrlDiscovery":false}'::jsonb,TRUE,clock_timestamp(),clock_timestamp());
 IF to_regclass('public.platform_organization_cleanup_steps') IS NOT NULL THEN
  ALTER TABLE platform_organization_cleanup_steps DROP CONSTRAINT ck_platform_organization_cleanup_step_key;
  ALTER TABLE platform_organization_cleanup_steps ADD CONSTRAINT ck_platform_organization_cleanup_step_key CHECK(step_key IN(
   'AUTOMATION_FREEZE_PURGE','RUNTIME_QUIESCE','ARTIFACT_PURGE','RUNTIME_PURGE','CONVERSATION_PURGE',
   'TOOLING_CONFIGURATION_PURGE','PROJECT_TASK_MEMORY_PURGE','PROJECT_EXTERNAL_AND_DATABASE_PURGE','AGENT_PURGE','INFERENCE_PURGE','GOVERNANCE_PURGE','IDENTITY_FINALIZE'));
  INSERT INTO platform_organization_cleanup_steps(organization_id,step_key,step_sequence,state,attempt,created_at,updated_at)
   SELECT organization_id,'TOOLING_CONFIGURATION_PURGE',550,'PENDING',0,clock_timestamp(),clock_timestamp()
   FROM platform_organization_cleanup_jobs WHERE state<>'COMPLETED' ON CONFLICT DO NOTHING;
 END IF;
END $mcp_marketplace$;
