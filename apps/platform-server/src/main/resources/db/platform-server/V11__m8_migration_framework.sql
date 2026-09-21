CREATE TABLE IF NOT EXISTS platform_migration_runs (
    run_id             VARCHAR(36) PRIMARY KEY,
    job_version        VARCHAR(40) NOT NULL,
    job_checksum       VARCHAR(64) NOT NULL,
    status             VARCHAR(24) NOT NULL,
    source_snapshot    TEXT NOT NULL,
    target_snapshot    TEXT,
    error_message      TEXT,
    started_at         TIMESTAMP NOT NULL,
    completed_at       TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_platform_migration_runs_started
    ON platform_migration_runs(started_at DESC);

CREATE TABLE IF NOT EXISTS platform_migration_watermarks (
    domain             VARCHAR(40) PRIMARY KEY,
    last_migrated_id   VARCHAR(255),
    source_count       BIGINT NOT NULL DEFAULT 0,
    target_count       BIGINT NOT NULL DEFAULT 0,
    source_checksum    VARCHAR(64),
    target_checksum    VARCHAR(64),
    status             VARCHAR(24) NOT NULL,
    started_at         TIMESTAMP NOT NULL,
    completed_at       TIMESTAMP,
    updated_at         TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS platform_migration_id_map (
    domain             VARCHAR(40) NOT NULL,
    entity_type        VARCHAR(80) NOT NULL,
    legacy_id          VARCHAR(255) NOT NULL,
    platform_id        VARCHAR(255) NOT NULL,
    migrated_at        TIMESTAMP NOT NULL,
    PRIMARY KEY (domain, entity_type, legacy_id),
    CONSTRAINT uk_platform_migration_target
        UNIQUE (domain, entity_type, platform_id)
);

CREATE INDEX IF NOT EXISTS idx_platform_migration_id_map_target
    ON platform_migration_id_map(domain, entity_type, platform_id);
