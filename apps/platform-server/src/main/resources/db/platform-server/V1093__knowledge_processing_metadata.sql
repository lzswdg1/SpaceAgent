ALTER TABLE platform_knowledge_generation_sources ADD COLUMN media_type VARCHAR(120) NOT NULL DEFAULT 'text/plain',
 ADD COLUMN source_charset VARCHAR(80) NOT NULL DEFAULT 'UTF-8',
 ADD COLUMN parse_metadata_reference VARCHAR(160), ADD COLUMN parse_metadata_hash CHAR(64),
 ADD COLUMN chunking_policy JSONB NOT NULL DEFAULT '{"strategy":"FIXED_CHARACTER","size":800,"overlap":100,"tokenizer":"NONE"}';
ALTER TABLE platform_knowledge_generation_chunks ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}'
 CHECK(jsonb_typeof(metadata)='object' AND octet_length(metadata::text)<=8192);
CREATE TABLE platform_knowledge_processing_policies (
 base_id VARCHAR(36) PRIMARY KEY REFERENCES platform_knowledge_bases(id) ON DELETE CASCADE,
 revision BIGINT NOT NULL CHECK(revision>0), strategy VARCHAR(32) NOT NULL,
 segment_size INT NOT NULL CHECK(segment_size BETWEEN 64 AND 2048), overlap_size INT NOT NULL CHECK(overlap_size BETWEEN 0 AND segment_size/2)
);
ALTER TABLE platform_knowledge_index_jobs DROP CONSTRAINT platform_knowledge_index_jobs_storage_schema_check;
ALTER TABLE platform_knowledge_index_jobs ADD CHECK(storage_schema IN ('dense_v1','hybrid_v2'));
ALTER TABLE platform_knowledge_index_tombstones ADD COLUMN storage_schema VARCHAR(40) NOT NULL DEFAULT 'dense_v1';
