DO $mcp_import$ BEGIN
 IF to_regclass('public.platform_mcp_connections') IS NULL
    OR to_regclass('public.platform_source_repositories') IS NULL THEN RETURN; END IF;

 CREATE TABLE platform_mcp_invocation_ledger(
  id UUID PRIMARY KEY,
  tenant_id VARCHAR(36) NOT NULL REFERENCES platform_tenants(id),
  user_id VARCHAR(36) NOT NULL REFERENCES platform_users(id),
  connection_id UUID NOT NULL REFERENCES platform_mcp_connections(id) ON DELETE CASCADE,
  operation_key VARCHAR(80) NOT NULL,
  idempotency_key_hash CHAR(64) NOT NULL,
  tool_name VARCHAR(160) NOT NULL,
  arguments_json JSONB NOT NULL,
  input_hash CHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL,
  result_json JSONB,
  error_code VARCHAR(100),
  claim_token UUID,
  claim_owner VARCHAR(160) NOT NULL,
  lease_until TIMESTAMPTZ NOT NULL,
  revision BIGINT NOT NULL DEFAULT 1,
  started_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  completed_at TIMESTAMPTZ,
  CONSTRAINT uk_mcp_invocation_idempotency
   UNIQUE(tenant_id,user_id,operation_key,idempotency_key_hash),
  CONSTRAINT ck_mcp_invocation_status
   CHECK(status IN ('RUNNING','SUCCEEDED','FAILED','UNKNOWN')),
  CONSTRAINT ck_mcp_invocation_claim
   CHECK(status<>'RUNNING' OR claim_token IS NOT NULL),
  CONSTRAINT ck_mcp_invocation_revision CHECK(revision>0)
 );
 CREATE INDEX idx_mcp_invocation_connection
  ON platform_mcp_invocation_ledger(connection_id,started_at DESC);
 CREATE INDEX idx_mcp_invocation_running_lease
  ON platform_mcp_invocation_ledger(lease_until) WHERE status='RUNNING';

 ALTER TABLE platform_source_repositories
  ADD COLUMN mcp_connection_id UUID,
  ADD COLUMN mcp_invocation_id UUID;
 COMMENT ON COLUMN platform_source_repositories.mcp_connection_id IS
  'Opaque Tooling connection reference validated at import; deliberately no cross-module FK';
 COMMENT ON COLUMN platform_source_repositories.mcp_invocation_id IS
  'Opaque Tooling invocation evidence reference; deliberately no cross-module FK';
END $mcp_import$;
