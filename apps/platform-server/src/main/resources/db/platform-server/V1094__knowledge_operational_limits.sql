ALTER TABLE platform_knowledge_index_intake_requests ADD COLUMN original_bytes BIGINT NOT NULL DEFAULT 0 CHECK(original_bytes BETWEEN 0 AND 8000000);
ALTER TABLE platform_knowledge_index_intake_requests ADD COLUMN legacy_source_document_id VARCHAR(36);
ALTER TABLE platform_knowledge_index_tombstones ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'DOCUMENT' CHECK(kind IN ('DOCUMENT','GENERATION'));
CREATE TABLE platform_knowledge_worker_fairness (
 tenant_key VARCHAR(80) PRIMARY KEY, last_claim_at TIMESTAMPTZ NOT NULL DEFAULT 'epoch'
);
CREATE TABLE platform_knowledge_query_leases (
 id VARCHAR(36) PRIMARY KEY, organization_id VARCHAR(36) NOT NULL, lease_until TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_knowledge_query_lease_tenant ON platform_knowledge_query_leases(organization_id,lease_until);
CREATE INDEX idx_knowledge_job_tenant_running ON platform_knowledge_index_jobs(organization_id,lease_until) WHERE state='RUNNING';
CREATE TABLE platform_knowledge_index_repairs (
 id VARCHAR(36) PRIMARY KEY, base_id VARCHAR(36) NOT NULL, document_id VARCHAR(36) NOT NULL,
 generation_id VARCHAR(36) NOT NULL, organization_id VARCHAR(36) NOT NULL, actor_id VARCHAR(36) NOT NULL,
 idempotency_key VARCHAR(80) NOT NULL, state VARCHAR(20) NOT NULL CHECK(state IN ('PENDING','RUNNING','COMPLETED','FAILED')),
 claim_token VARCHAR(36), fence BIGINT NOT NULL DEFAULT 0, lease_until TIMESTAMPTZ, safe_code VARCHAR(80),
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(base_id,actor_id,idempotency_key)
);
