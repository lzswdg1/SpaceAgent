ALTER TABLE platform_knowledge_chunks
    ADD COLUMN IF NOT EXISTS content_hash CHAR(64),
    ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(160),
    ADD COLUMN IF NOT EXISTS embedding_dimensions INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS embedding_vector TEXT NOT NULL DEFAULT '[]';

CREATE INDEX IF NOT EXISTS idx_platform_knowledge_chunks_content_hash
    ON platform_knowledge_chunks(document_id, content_hash);

CREATE INDEX IF NOT EXISTS idx_platform_knowledge_documents_owner_status
    ON platform_knowledge_documents(owner_id, status, created_at DESC);
