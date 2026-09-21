CREATE TABLE IF NOT EXISTS platform_memory_candidates (
    id          VARCHAR(36) PRIMARY KEY,
    scope_type  VARCHAR(16) NOT NULL,
    scope_id    VARCHAR(36) NOT NULL,
    kind        VARCHAR(32) NOT NULL,
    source_id   VARCHAR(160) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    content     TEXT NOT NULL,
    confidence  DOUBLE PRECISION NOT NULL,
    dedupe_key  VARCHAR(240) NOT NULL,
    state       VARCHAR(24) NOT NULL,
    created_at  TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_platform_memory_candidates_scope_state
    ON platform_memory_candidates(scope_type, scope_id, state, created_at);

CREATE TABLE IF NOT EXISTS platform_consolidated_memories (
    id          VARCHAR(36) PRIMARY KEY,
    scope_type  VARCHAR(16) NOT NULL,
    scope_id    VARCHAR(36) NOT NULL,
    kind        VARCHAR(32) NOT NULL,
    memory_key  VARCHAR(240) NOT NULL,
    memory_value TEXT NOT NULL,
    created_at  TIMESTAMP NOT NULL,
    updated_at  TIMESTAMP NOT NULL,
    CONSTRAINT uk_platform_consolidated_memory_scope_key
        UNIQUE (scope_type, scope_id, kind, memory_key)
);

CREATE INDEX IF NOT EXISTS idx_platform_consolidated_memories_scope
    ON platform_consolidated_memories(scope_type, scope_id, updated_at DESC);
