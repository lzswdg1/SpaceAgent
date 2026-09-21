DO $project_local_materialization_chunks$
BEGIN
    IF to_regclass('public.platform_project_local_materialization_sessions') IS NULL THEN
        RETURN;
    END IF;

    CREATE TABLE platform_project_local_materialization_chunks (
        id              UUID PRIMARY KEY,
        session_id      UUID NOT NULL,
        request_id      VARCHAR(200) NOT NULL,
        relative_path   VARCHAR(1024) NOT NULL,
        byte_offset     BIGINT NOT NULL,
        content_length  BIGINT NOT NULL,
        content_sha256  VARCHAR(71) NOT NULL,
        staging_key     CHAR(64) NOT NULL,
        state           VARCHAR(16) NOT NULL,
        blocked_code    VARCHAR(120),
        revision        BIGINT NOT NULL,
        created_at      TIMESTAMPTZ NOT NULL,
        updated_at      TIMESTAMPTZ NOT NULL,
        CONSTRAINT uk_project_local_materialization_chunk_request UNIQUE (session_id, request_id),
        CONSTRAINT uk_project_local_materialization_chunk_staging UNIQUE (session_id, staging_key),
        CONSTRAINT fk_project_local_materialization_chunk_session
            FOREIGN KEY (session_id) REFERENCES platform_project_local_materialization_sessions(id) ON DELETE CASCADE,
        CONSTRAINT ck_project_local_materialization_chunk_bounds
            CHECK (byte_offset >= 0 AND content_length BETWEEN 1 AND 8388608
                AND content_sha256 ~ '^sha256:[0-9a-f]{64}$' AND staging_key ~ '^[0-9a-f]{64}$'
                AND revision > 0),
        CONSTRAINT ck_project_local_materialization_chunk_state
            CHECK ((state = 'STORED' AND blocked_code IS NULL)
                OR (state = 'BLOCKED' AND blocked_code IS NOT NULL))
    );

    CREATE INDEX idx_project_local_materialization_chunk_session
        ON platform_project_local_materialization_chunks(session_id, created_at, id);

    COMMENT ON TABLE platform_project_local_materialization_chunks IS
        'Project-owned bounded Local Bridge chunk metadata; byte files use only server-derived staging keys';
END
$project_local_materialization_chunks$;
