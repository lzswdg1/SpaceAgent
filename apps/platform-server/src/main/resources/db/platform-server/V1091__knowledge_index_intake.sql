-- Existing owner-private rows stay unchanged. Only new organization-owned sources may omit a user owner.
ALTER TABLE platform_knowledge_documents ALTER COLUMN owner_id DROP NOT NULL;
ALTER TABLE platform_knowledge_document_scopes ALTER COLUMN original_owner_id DROP NOT NULL;
ALTER TABLE platform_knowledge_document_scopes ADD CONSTRAINT fk_knowledge_scope_document
 FOREIGN KEY(document_id) REFERENCES platform_knowledge_documents(id) ON DELETE CASCADE;

CREATE FUNCTION check_knowledge_document_ownership() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE document_key VARCHAR(36); document_keys VARCHAR(36)[];
BEGIN
 IF TG_TABLE_NAME='platform_knowledge_bases' THEN
   IF EXISTS(SELECT 1 FROM platform_knowledge_document_scopes s JOIN platform_knowledge_bases b ON b.id=s.base_id
       JOIN platform_knowledge_documents d ON d.id=s.document_id WHERE b.id=NEW.id AND b.scope<>'ORGANIZATION' AND d.owner_id IS NULL) THEN
     RAISE EXCEPTION 'Organization-owned sources cannot move into a personal base' USING ERRCODE='23514';
   END IF;
   RETURN NULL;
 END IF;
 IF TG_TABLE_NAME='platform_knowledge_documents' THEN
   document_keys=ARRAY[NEW.id];
 ELSE
   IF TG_OP='DELETE' THEN document_keys=ARRAY[OLD.document_id];
   ELSIF TG_OP='UPDATE' THEN document_keys=ARRAY[OLD.document_id,NEW.document_id];
   ELSE document_keys=ARRAY[NEW.document_id]; END IF;
 END IF;
 FOREACH document_key IN ARRAY document_keys LOOP
 IF EXISTS(SELECT 1 FROM platform_knowledge_documents d WHERE d.id=document_key AND d.owner_id IS NULL)
    AND NOT EXISTS(SELECT 1 FROM platform_knowledge_document_scopes s JOIN platform_knowledge_bases b ON b.id=s.base_id
      WHERE s.document_id=document_key AND s.original_owner_id IS NULL AND s.provenance='EXPLICIT' AND b.scope='ORGANIZATION') THEN
   RAISE EXCEPTION 'Ownerless Knowledge document requires an explicit organization scope' USING ERRCODE='23514';
 END IF;
 END LOOP;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER knowledge_document_ownership
 AFTER INSERT OR UPDATE ON platform_knowledge_documents DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION check_knowledge_document_ownership();
CREATE CONSTRAINT TRIGGER knowledge_scope_ownership
 AFTER INSERT OR UPDATE OR DELETE ON platform_knowledge_document_scopes DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION check_knowledge_document_ownership();
CREATE CONSTRAINT TRIGGER knowledge_base_document_ownership
 AFTER UPDATE ON platform_knowledge_bases DEFERRABLE INITIALLY DEFERRED
 FOR EACH ROW EXECUTE FUNCTION check_knowledge_document_ownership();

CREATE TABLE platform_knowledge_index_intake_requests (
 base_id VARCHAR(36) NOT NULL REFERENCES platform_knowledge_bases(id),
 actor_id VARCHAR(36) NOT NULL, idempotency_key VARCHAR(100) NOT NULL,
 request_hash CHAR(64) NOT NULL CHECK(request_hash ~ '^[a-f0-9]{64}$'),
 document_id VARCHAR(36) NOT NULL REFERENCES platform_knowledge_documents(id),
 generation_id VARCHAR(36) NOT NULL REFERENCES platform_knowledge_index_generations(id),
 job_id VARCHAR(36) NOT NULL REFERENCES platform_knowledge_index_jobs(id),
 document_revision BIGINT NOT NULL CHECK(document_revision>0),
 source_kind VARCHAR(24) NOT NULL CHECK(source_kind IN ('FILE','URL_SNAPSHOT')),
 source_id VARCHAR(36), media_type VARCHAR(100) NOT NULL, source_charset VARCHAR(80) NOT NULL,
 normalization_version VARCHAR(32) NOT NULL DEFAULT 'NORMALIZATION_V1',
 original_hash CHAR(64) NOT NULL CHECK(original_hash ~ '^[a-f0-9]{64}$'),
 original_reference VARCHAR(160) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(base_id,actor_id,idempotency_key),
 UNIQUE(document_id,document_revision),
 FOREIGN KEY(generation_id,base_id) REFERENCES platform_knowledge_index_generations(id,base_id)
);
