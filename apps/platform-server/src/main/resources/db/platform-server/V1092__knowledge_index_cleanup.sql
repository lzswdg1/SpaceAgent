ALTER TABLE platform_knowledge_document_heads DROP CONSTRAINT platform_knowledge_document_heads_state_check;
ALTER TABLE platform_knowledge_document_heads ADD CHECK(state IN ('ACTIVE','DELETING','DELETED'));
-- No FK to source rows: this minimal marker must survive metadata/user/organization erasure.
CREATE TABLE platform_knowledge_index_tombstones (
 generation_id VARCHAR(36) PRIMARY KEY, base_id VARCHAR(36) NOT NULL, document_id VARCHAR(36) NOT NULL,
 job_id VARCHAR(36), request_tenant VARCHAR(36), request_actor VARCHAR(36), space_id VARCHAR(36) NOT NULL, space_fingerprint CHAR(64) NOT NULL,
 dimensions INT NOT NULL, scope_key VARCHAR(96) NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','CLEAN')),
 clean_passes INT NOT NULL DEFAULT 0, next_check_at TIMESTAMPTZ NOT NULL,
 claim_token VARCHAR(36), lease_until TIMESTAMPTZ, fence BIGINT NOT NULL DEFAULT 0,
 safe_code VARCHAR(80), created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_knowledge_tombstone_due ON platform_knowledge_index_tombstones(next_check_at);
CREATE INDEX idx_knowledge_tombstone_document ON platform_knowledge_index_tombstones(base_id,document_id);
CREATE TABLE platform_knowledge_object_inventory (
 reference VARCHAR(160) PRIMARY KEY, owner_id VARCHAR(36) NOT NULL, last_seen_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_knowledge_object_gc ON platform_knowledge_object_inventory(last_seen_at);
CREATE FUNCTION prohibit_deleted_knowledge_document_reuse() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM platform_knowledge_index_tombstones WHERE document_id=NEW.document_id) THEN
   RAISE EXCEPTION 'Deleted index document identity is permanently reserved' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER knowledge_no_deleted_identity_reuse BEFORE INSERT ON platform_knowledge_document_heads
 FOR EACH ROW EXECUTE FUNCTION prohibit_deleted_knowledge_document_reuse();
CREATE TABLE platform_knowledge_index_reconciliations (
 id BIGSERIAL PRIMARY KEY, job_id VARCHAR(36) NOT NULL, actor_id VARCHAR(36) NOT NULL,
 outcome VARCHAR(80) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE platform_embedding_output_tombstones (
 tenant_id VARCHAR(36) NOT NULL, actor_id VARCHAR(36) NOT NULL, operation_prefix VARCHAR(80) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(), PRIMARY KEY(tenant_id,actor_id,operation_prefix)
);
ALTER TABLE platform_embedding_calls ADD COLUMN response_erased BOOLEAN NOT NULL DEFAULT false;
DO $$ DECLARE c RECORD; BEGIN
 FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='platform_embedding_calls'::regclass
    AND contype='c' AND pg_get_constraintdef(oid) LIKE '%SUCCEEDED%encrypted_response%IS NOT NULL%' LOOP
   EXECUTE format('ALTER TABLE platform_embedding_calls DROP CONSTRAINT %I',c.conname);
 END LOOP;
END $$;
ALTER TABLE platform_embedding_calls ADD CONSTRAINT ck_embedding_response_retention
 CHECK(state<>'SUCCEEDED' OR encrypted_response IS NOT NULL OR response_erased);
