CREATE TABLE IF NOT EXISTS platform_knowledge_documents (
    id                 VARCHAR(36) PRIMARY KEY,
    owner_id           VARCHAR(36) NOT NULL,
    name               VARCHAR(255) NOT NULL,
    content_type       VARCHAR(100) NOT NULL,
    storage_location   VARCHAR(1000) NOT NULL,
    status             VARCHAR(24) NOT NULL,
    error_reason       VARCHAR(500),
    created_at         TIMESTAMP NOT NULL,
    updated_at         TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_platform_knowledge_documents_owner
    ON platform_knowledge_documents(owner_id, created_at DESC);

CREATE TABLE IF NOT EXISTS platform_knowledge_chunks (
    id                    VARCHAR(36) PRIMARY KEY,
    document_id           VARCHAR(36) NOT NULL REFERENCES platform_knowledge_documents(id) ON DELETE CASCADE,
    sequence_number       INTEGER NOT NULL,
    content               TEXT NOT NULL,
    embedding_reference   VARCHAR(1000),
    created_at            TIMESTAMP NOT NULL,
    CONSTRAINT uk_platform_knowledge_chunk_document_sequence UNIQUE (document_id, sequence_number)
);

CREATE INDEX IF NOT EXISTS idx_platform_knowledge_chunks_document
    ON platform_knowledge_chunks(document_id, sequence_number);
