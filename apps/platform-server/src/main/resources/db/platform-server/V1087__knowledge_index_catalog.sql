-- Legacy document references remain document IDs. No vectors are assigned a guessed model space.
ALTER TABLE platform_knowledge_documents ADD CONSTRAINT uk_knowledge_document_owner UNIQUE(id, owner_id);

CREATE TABLE platform_knowledge_document_scopes (
    document_id VARCHAR(36) PRIMARY KEY,
    base_id VARCHAR(36) NOT NULL REFERENCES platform_knowledge_bases(id) ON DELETE CASCADE,
    original_owner_id VARCHAR(36) NOT NULL,
    provenance VARCHAR(32) NOT NULL CHECK(provenance IN ('LEGACY_PRIVATE','EXPLICIT')),
    assigned_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(document_id, original_owner_id) REFERENCES platform_knowledge_documents(id, owner_id) ON DELETE CASCADE,
    UNIQUE(document_id, base_id)
);
CREATE INDEX idx_knowledge_document_scope_base ON platform_knowledge_document_scopes(base_id, document_id);

INSERT INTO platform_knowledge_bases(id, scope, organization_id, owner_id, name, description, state, revision, created_at, updated_at)
SELECT md5('knowledge-personal:' || owner_id)::uuid::text, 'PERSONAL', NULL, owner_id,
       'Personal documents', 'Private compatibility collection for legacy documents', 'ACTIVE', 1, now(), now()
FROM platform_knowledge_documents GROUP BY owner_id;
INSERT INTO platform_knowledge_document_scopes(document_id, base_id, original_owner_id, provenance, assigned_at)
SELECT id, md5('knowledge-personal:' || owner_id)::uuid::text, owner_id, 'LEGACY_PRIVATE', now()
FROM platform_knowledge_documents;

CREATE TABLE platform_knowledge_embedding_spaces (
    id VARCHAR(36) PRIMARY KEY,
    base_id VARCHAR(36) NOT NULL REFERENCES platform_knowledge_bases(id) ON DELETE CASCADE,
    provider_id VARCHAR(100) NOT NULL,
    model_id VARCHAR(160) NOT NULL,
    model_revision VARCHAR(160) NOT NULL,
    provider_fingerprint CHAR(64) NOT NULL CHECK(provider_fingerprint ~ '^[0-9a-f]{64}$'),
    dimensions INTEGER NOT NULL CHECK(dimensions BETWEEN 1 AND 32768),
    preprocessing_hash CHAR(64) NOT NULL CHECK(preprocessing_hash ~ '^[0-9a-f]{64}$'),
    fingerprint CHAR(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE(base_id, fingerprint), UNIQUE(id, base_id)
);
CREATE TABLE platform_knowledge_index_generations (
    id VARCHAR(36) PRIMARY KEY,
    base_id VARCHAR(36) NOT NULL,
    document_id VARCHAR(36) NOT NULL,
    space_id VARCHAR(36) NOT NULL,
    content_hash CHAR(64) NOT NULL CHECK(content_hash ~ '^[0-9a-f]{64}$'),
    parser_fingerprint CHAR(64) NOT NULL CHECK(parser_fingerprint ~ '^[0-9a-f]{64}$'),
    chunking_fingerprint CHAR(64) NOT NULL CHECK(chunking_fingerprint ~ '^[0-9a-f]{64}$'),
    fingerprint CHAR(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    FOREIGN KEY(document_id, base_id) REFERENCES platform_knowledge_document_scopes(document_id, base_id) ON DELETE CASCADE,
    FOREIGN KEY(space_id, base_id) REFERENCES platform_knowledge_embedding_spaces(id, base_id) ON DELETE CASCADE,
    UNIQUE(base_id, fingerprint)
);
CREATE INDEX idx_knowledge_generations_document ON platform_knowledge_index_generations(base_id, document_id, id);
COMMENT ON TABLE platform_knowledge_embedding_spaces IS 'Registered expected model spaces; capability is not verified until indexing performs an embedding request';
COMMENT ON TABLE platform_knowledge_index_generations IS 'Immutable staged descriptors, not published indexes; verified indexing jobs will own activation';

ALTER TABLE platform_agent_current_configurations ADD COLUMN knowledge_collection_ids JSONB NOT NULL DEFAULT '[]'
    CHECK(jsonb_typeof(knowledge_collection_ids)='array' AND jsonb_array_length(knowledge_collection_ids)<=64);
ALTER TABLE platform_agent_run_configuration_snapshots ADD COLUMN knowledge_collection_ids JSONB
    CHECK(knowledge_collection_ids IS NULL OR (jsonb_typeof(knowledge_collection_ids)='array'
        AND jsonb_array_length(knowledge_collection_ids)<=64));
UPDATE platform_agent_run_configuration_snapshots SET knowledge_collection_ids='[]' WHERE snapshot_state='SNAPSHOTTED';
