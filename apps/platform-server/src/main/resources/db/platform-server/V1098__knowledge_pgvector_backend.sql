-- PostgreSQL is already the authoritative business runtime with pgvector installed by database-init.
-- Use the public extension schema explicitly so isolated application schemas work identically.
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

CREATE TABLE platform_knowledge_vector_backend (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK(singleton),
    mode VARCHAR(16) NOT NULL CHECK(mode IN ('milvus','pgvector')),
    selected_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
-- Old generations belong to the previously supported Milvus deployment. Never reinterpret them.
INSERT INTO platform_knowledge_vector_backend(singleton,mode)
SELECT TRUE,'milvus' WHERE EXISTS(SELECT 1 FROM platform_knowledge_index_generations);

CREATE TABLE platform_knowledge_pgvector_spaces (
    space_id VARCHAR(36) NOT NULL,
    schema_kind VARCHAR(40) NOT NULL CHECK(schema_kind IN ('dense_v1','hybrid_v2')),
    fingerprint CHAR(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),
    dimensions INTEGER NOT NULL CHECK(dimensions BETWEEN 1 AND 16000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(space_id,schema_kind),
    UNIQUE(space_id,schema_kind,dimensions)
);
-- This is a rebuildable sensitive index replica, not the source/activation/authorization authority.
-- No FK to source metadata: deletion must retain its tombstone and converge late replica writes.
CREATE TABLE platform_knowledge_pgvector_entries (
    space_id VARCHAR(36) NOT NULL,
    schema_kind VARCHAR(40) NOT NULL,
    dimensions INTEGER NOT NULL,
    scope_key VARCHAR(96) NOT NULL,
    base_id VARCHAR(36) NOT NULL,
    document_id VARCHAR(36) NOT NULL,
    generation_id VARCHAR(36) NOT NULL,
    chunk_id VARCHAR(36) NOT NULL,
    content_hash CHAR(64) NOT NULL CHECK(content_hash ~ '^[0-9a-f]{64}$'),
    payload_hash CHAR(64) NOT NULL CHECK(payload_hash ~ '^[0-9a-f]{64}$'),
    embedding public.vector NOT NULL CHECK(public.vector_dims(embedding)=dimensions),
    lexical tsvector NOT NULL,
    PRIMARY KEY(space_id,schema_kind,scope_key,base_id,generation_id,chunk_id),
    FOREIGN KEY(space_id,schema_kind,dimensions)
        REFERENCES platform_knowledge_pgvector_spaces(space_id,schema_kind,dimensions)
);
CREATE INDEX idx_knowledge_pgvector_scope ON platform_knowledge_pgvector_entries
    (scope_key,base_id,generation_id,space_id,schema_kind);
CREATE INDEX idx_knowledge_pgvector_lexical ON platform_knowledge_pgvector_entries USING GIN(lexical);
