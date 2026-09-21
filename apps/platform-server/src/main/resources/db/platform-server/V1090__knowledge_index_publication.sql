-- An internal publication boundary; no automatic ingestion or hard deletion is enabled by this migration.
CREATE TABLE platform_knowledge_document_heads (
 document_id VARCHAR(36) PRIMARY KEY,
 base_id VARCHAR(36) NOT NULL,
 revision BIGINT NOT NULL CHECK(revision>0),
 state VARCHAR(20) NOT NULL CHECK(state IN ('ACTIVE','DELETING')),
 content_hash CHAR(64) NOT NULL CHECK(content_hash ~ '^[a-f0-9]{64}$'),
 active_generation_id VARCHAR(36),
 FOREIGN KEY(document_id,base_id) REFERENCES platform_knowledge_document_scopes(document_id,base_id),
 FOREIGN KEY(active_generation_id,base_id) REFERENCES platform_knowledge_index_generations(id,base_id),
 CHECK(state='ACTIVE' OR active_generation_id IS NULL)
);
CREATE TABLE platform_knowledge_generation_sources (
 generation_id VARCHAR(36) PRIMARY KEY REFERENCES platform_knowledge_index_generations(id),
 document_revision BIGINT NOT NULL CHECK(document_revision>0),
 source_reference VARCHAR(160) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE platform_knowledge_generation_chunks (
 generation_id VARCHAR(36) NOT NULL REFERENCES platform_knowledge_generation_sources(generation_id),
 chunk_id VARCHAR(36) NOT NULL, ordinal INT NOT NULL CHECK(ordinal BETWEEN 0 AND 32767),
 content TEXT NOT NULL CHECK(length(content)>0 AND length(content)<=8192),
 content_hash CHAR(64) NOT NULL CHECK(content_hash ~ '^[a-f0-9]{64}$'),
 PRIMARY KEY(generation_id,chunk_id), UNIQUE(generation_id,ordinal)
);
CREATE INDEX idx_knowledge_heads_visible ON platform_knowledge_document_heads(base_id,document_id) WHERE state='ACTIVE';
