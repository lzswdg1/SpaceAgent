-- Governance append-only transport evidence; no bodies, names, credentials, or foreign-owner FKs.
CREATE TABLE platform_governance_business_attempts (
 id VARCHAR(36) PRIMARY KEY, actor_id VARCHAR(36) NOT NULL, tenant_id VARCHAR(36),
 actor_kind VARCHAR(20) NOT NULL CHECK(actor_kind IN ('USER','SYSTEM_ADMIN')),
 method VARCHAR(8) NOT NULL, route VARCHAR(240) NOT NULL, resource_kind VARCHAR(40) NOT NULL,
 resource_id VARCHAR(36), created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE platform_governance_business_outcomes (
 attempt_id VARCHAR(36) PRIMARY KEY REFERENCES platform_governance_business_attempts(id),
 http_status INT NOT NULL CHECK(http_status BETWEEN 100 AND 599),
 outcome VARCHAR(32) NOT NULL CHECK(outcome IN ('HTTP_ACCEPTED','HTTP_SUCCEEDED','HTTP_REJECTED','HTTP_FAILED')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_business_attempt_actor ON platform_governance_business_attempts(actor_id,created_at DESC,id);
CREATE INDEX idx_business_attempt_tenant ON platform_governance_business_attempts(tenant_id,created_at DESC,id);
CREATE FUNCTION reject_business_evidence_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'business evidence is append only'; END $$;
CREATE TRIGGER business_attempt_immutable BEFORE UPDATE OR DELETE ON platform_governance_business_attempts FOR EACH ROW EXECUTE FUNCTION reject_business_evidence_mutation();
CREATE TRIGGER business_outcome_immutable BEFORE UPDATE OR DELETE ON platform_governance_business_outcomes FOR EACH ROW EXECUTE FUNCTION reject_business_evidence_mutation();

-- One-time attribution copied from Runtime authority. New claims resolve scope through the public owner port.
ALTER TABLE platform_model_call_ledger ADD COLUMN usage_tenant_id VARCHAR(36), ADD COLUMN usage_actor_id VARCHAR(36);
ALTER TABLE platform_tool_execution_ledger ADD COLUMN usage_tenant_id VARCHAR(36), ADD COLUMN usage_actor_id VARCHAR(36);
UPDATE platform_model_call_ledger c SET usage_tenant_id=r.tenant_id,usage_actor_id=r.owner_id FROM platform_agent_runs r WHERE r.id=c.agent_run_id;
UPDATE platform_tool_execution_ledger c SET usage_tenant_id=r.tenant_id,usage_actor_id=r.owner_id FROM platform_agent_runs r WHERE r.id=c.agent_run_id;
CREATE INDEX idx_model_admin_usage ON platform_model_call_ledger(usage_tenant_id,usage_actor_id,created_at DESC,id);
CREATE INDEX idx_tool_admin_usage ON platform_tool_execution_ledger(usage_tenant_id,usage_actor_id,started_at DESC,id);
CREATE INDEX idx_embedding_admin_usage ON platform_embedding_calls(tenant_id,actor_id,created_at DESC,id);
CREATE FUNCTION immutable_usage_attribution() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.usage_actor_id IS NOT NULL AND (OLD.usage_actor_id IS DISTINCT FROM NEW.usage_actor_id OR OLD.usage_tenant_id IS DISTINCT FROM NEW.usage_tenant_id)
 THEN RAISE EXCEPTION 'usage attribution is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER model_usage_attribution_immutable BEFORE UPDATE ON platform_model_call_ledger FOR EACH ROW EXECUTE FUNCTION immutable_usage_attribution();
CREATE TRIGGER tool_usage_attribution_immutable BEFORE UPDATE ON platform_tool_execution_ledger FOR EACH ROW EXECUTE FUNCTION immutable_usage_attribution();
