-- PostgreSQL owns durable dispatch and stage recovery. This does not publish an index.
ALTER TABLE platform_knowledge_index_generations ADD CONSTRAINT uk_knowledge_generation_base UNIQUE(id,base_id);
CREATE TABLE platform_knowledge_index_jobs (
    id VARCHAR(36) PRIMARY KEY,
    base_id VARCHAR(36) NOT NULL,
    generation_id VARCHAR(36) NOT NULL UNIQUE,
    requested_by VARCHAR(36) NOT NULL,
    organization_id VARCHAR(36),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
    storage_schema VARCHAR(40) NOT NULL CHECK(storage_schema='dense_v1'),
    max_attempts INT NOT NULL CHECK(max_attempts BETWEEN 1 AND 8),
    stage VARCHAR(32) NOT NULL CHECK(stage IN ('PARSING','CHUNKING','EMBEDDING','WRITING','VERIFYING','READY_TO_ACTIVATE')),
    state VARCHAR(40) NOT NULL CHECK(state IN ('QUEUED','RUNNING','RETRY_WAIT','FAILED','CANCELLED','RECONCILIATION_REQUIRED','COMPLETED')),
    revision BIGINT NOT NULL CHECK(revision>0),
    fence BIGINT NOT NULL CHECK(fence>=0),
    worker VARCHAR(100), lease_until TIMESTAMPTZ,
    attempts INT NOT NULL CHECK(attempts>=0 AND attempts<=max_attempts),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    error_code VARCHAR(80) CHECK(error_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
    CHECK((state='RUNNING' AND worker IS NOT NULL AND lease_until IS NOT NULL)
       OR (state<>'RUNNING' AND worker IS NULL AND lease_until IS NULL)),
    FOREIGN KEY(generation_id,base_id) REFERENCES platform_knowledge_index_generations(id,base_id),
    UNIQUE(base_id,requested_by,idempotency_key)
);
CREATE INDEX idx_knowledge_index_jobs_scope ON platform_knowledge_index_jobs(base_id,created_at,id);
CREATE INDEX idx_knowledge_index_jobs_due ON platform_knowledge_index_jobs(next_attempt_at,lease_until)
    WHERE state IN ('QUEUED','RETRY_WAIT','RUNNING');
-- A single durable work intent per immutable generation. Delivery is at-least-once, not a broker.
CREATE TABLE platform_knowledge_index_outbox (
    job_id VARCHAR(36) PRIMARY KEY REFERENCES platform_knowledge_index_jobs(id),
    pending BOOLEAN NOT NULL DEFAULT TRUE,
    deliveries INT NOT NULL DEFAULT 0 CHECK(deliveries>=0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE platform_knowledge_index_batches (
    job_id VARCHAR(36) NOT NULL REFERENCES platform_knowledge_index_jobs(id),
    stage VARCHAR(32) NOT NULL CHECK(stage IN ('PARSING','CHUNKING','EMBEDDING','WRITING','VERIFYING')),
    ordinal INT NOT NULL CHECK(ordinal BETWEEN 0 AND 511),
    input_hash CHAR(64) NOT NULL CHECK(input_hash ~ '^[0-9a-f]{64}$'),
    item_count INT NOT NULL CHECK(item_count BETWEEN 1 AND 64),
    state VARCHAR(20) NOT NULL CHECK(state IN ('IN_FLIGHT','COMPLETED','UNKNOWN')),
    output_reference VARCHAR(160), output_hash CHAR(64),
    CHECK((state='COMPLETED' AND output_reference IS NOT NULL AND output_hash IS NOT NULL
           AND starts_with(output_reference,'knowledge-index/' || job_id || '/') AND output_hash ~ '^[0-9a-f]{64}$')
       OR (state<>'COMPLETED' AND output_reference IS NULL AND output_hash IS NULL)),
    PRIMARY KEY(job_id,stage,ordinal)
);
CREATE TABLE platform_knowledge_index_manifests (
    job_id VARCHAR(36) NOT NULL REFERENCES platform_knowledge_index_jobs(id),
    stage VARCHAR(32) NOT NULL CHECK(stage IN ('PARSING','CHUNKING','EMBEDDING','WRITING','VERIFYING')),
    input_hash CHAR(64) NOT NULL CHECK(input_hash ~ '^[0-9a-f]{64}$'),
    batch_count INT NOT NULL CHECK(batch_count BETWEEN 1 AND 512),
    PRIMARY KEY(job_id,stage)
);
ALTER TABLE platform_knowledge_index_batches ADD FOREIGN KEY(job_id,stage)
    REFERENCES platform_knowledge_index_manifests(job_id,stage);
-- RESTRICT deliberately retains recovery evidence until PR2-U03's deletion coordinator can clean external effects.
