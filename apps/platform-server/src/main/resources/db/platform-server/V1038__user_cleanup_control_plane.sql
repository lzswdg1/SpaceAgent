DO $user_cleanup_control_plane$
BEGIN
    IF to_regclass('public.platform_users') IS NULL THEN
        RETURN;
    END IF;

    CREATE TABLE platform_user_cleanup_jobs (
        user_id               VARCHAR(36) PRIMARY KEY REFERENCES platform_users(id),
        command_id            UUID NOT NULL UNIQUE,
        requested_by          UUID NOT NULL,
        reason_hash           CHAR(64) NOT NULL,
        state                 VARCHAR(20) NOT NULL,
        retention_not_before  TIMESTAMPTZ NOT NULL,
        next_attempt_at       TIMESTAMPTZ NOT NULL,
        attempt               INTEGER NOT NULL DEFAULT 0,
        max_attempts          INTEGER NOT NULL,
        lease_owner           VARCHAR(160),
        lease_token           UUID,
        fencing_token         BIGINT NOT NULL DEFAULT 0,
        lease_until           TIMESTAMPTZ,
        last_error_code       VARCHAR(64),
        last_error_summary    VARCHAR(500),
        revision              BIGINT NOT NULL DEFAULT 0,
        created_at            TIMESTAMPTZ NOT NULL,
        updated_at            TIMESTAMPTZ NOT NULL,
        completed_at          TIMESTAMPTZ,
        CONSTRAINT ck_platform_user_cleanup_state
            CHECK (state IN ('PENDING', 'CLAIMED', 'RETRY', 'BLOCKED', 'COMPLETED')),
        CONSTRAINT ck_platform_user_cleanup_hash CHECK (reason_hash ~ '^[0-9a-f]{64}$'),
        CONSTRAINT ck_platform_user_cleanup_attempt CHECK (
            attempt >= 0 AND max_attempts BETWEEN 1 AND 100 AND attempt <= max_attempts),
        CONSTRAINT ck_platform_user_cleanup_revision
            CHECK (fencing_token >= 0 AND revision >= 0),
        CONSTRAINT ck_platform_user_cleanup_claim CHECK (
            (state = 'CLAIMED' AND lease_owner IS NOT NULL AND lease_token IS NOT NULL
                AND lease_until IS NOT NULL AND fencing_token > 0)
            OR
            (state <> 'CLAIMED' AND lease_owner IS NULL AND lease_token IS NULL
                AND lease_until IS NULL)),
        CONSTRAINT ck_platform_user_cleanup_completion CHECK (
            (state = 'COMPLETED' AND completed_at IS NOT NULL)
            OR (state <> 'COMPLETED' AND completed_at IS NULL)),
        CONSTRAINT ck_platform_user_cleanup_error CHECK (
            (last_error_code IS NULL AND last_error_summary IS NULL)
            OR (last_error_code ~ '^[A-Z][A-Z0-9_]{2,63}$'
                AND last_error_summary IS NOT NULL))
    );

    CREATE INDEX idx_platform_user_cleanup_due
        ON platform_user_cleanup_jobs(next_attempt_at, created_at, user_id)
        WHERE state IN ('PENDING', 'RETRY');
    CREATE INDEX idx_platform_user_cleanup_expired_claim
        ON platform_user_cleanup_jobs(lease_until, user_id)
        WHERE state = 'CLAIMED';

    CREATE TABLE platform_user_cleanup_steps (
        user_id            VARCHAR(36) NOT NULL,
        step_key           VARCHAR(80) NOT NULL,
        step_sequence      INTEGER NOT NULL,
        state              VARCHAR(20) NOT NULL,
        attempt            INTEGER NOT NULL DEFAULT 0,
        last_error_code    VARCHAR(64),
        last_error_summary VARCHAR(500),
        created_at         TIMESTAMPTZ NOT NULL,
        updated_at         TIMESTAMPTZ NOT NULL,
        completed_at       TIMESTAMPTZ,
        PRIMARY KEY (user_id, step_key),
        CONSTRAINT fk_platform_user_cleanup_step_job
            FOREIGN KEY (user_id) REFERENCES platform_user_cleanup_jobs(user_id) ON DELETE CASCADE,
        CONSTRAINT uk_platform_user_cleanup_step_sequence UNIQUE (user_id, step_sequence),
        CONSTRAINT ck_platform_user_cleanup_step_key CHECK (step_key IN (
            'AUTH_FREEZE', 'AUTOMATION_FREEZE_PURGE', 'RUNTIME_QUIESCE',
            'OWNERSHIP_MEMBERSHIP_RESOLUTION', 'ARTIFACT_PURGE', 'RUNTIME_PURGE',
            'CONVERSATION_PRIVATE_PURGE', 'USER_MEMORY_PURGE', 'KNOWLEDGE_PRIVATE_PURGE',
            'PROJECT_PRIVATE_RESOURCE_PURGE', 'AGENT_PRIVATE_RESOURCE_PURGE',
            'INFERENCE_PRIVATE_RESOURCE_PURGE', 'TOOLING_PRIVATE_RESOURCE_PURGE',
            'GOVERNANCE_PRIVATE_RESOURCE_PURGE', 'IDENTITY_FINALIZE_USER_TOMBSTONE')),
        CONSTRAINT ck_platform_user_cleanup_step_state CHECK (state IN ('PENDING', 'COMPLETED')),
        CONSTRAINT ck_platform_user_cleanup_step_attempt CHECK (attempt >= 0),
        CONSTRAINT ck_platform_user_cleanup_step_completion CHECK (
            (state = 'COMPLETED' AND completed_at IS NOT NULL)
            OR (state = 'PENDING' AND completed_at IS NULL)),
        CONSTRAINT ck_platform_user_cleanup_step_error CHECK (
            (last_error_code IS NULL AND last_error_summary IS NULL)
            OR (last_error_code ~ '^[A-Z][A-Z0-9_]{2,63}$'
                AND last_error_summary IS NOT NULL))
    );

    CREATE INDEX idx_platform_user_cleanup_step_pending
        ON platform_user_cleanup_steps(user_id, step_sequence)
        WHERE state = 'PENDING';

    COMMENT ON TABLE platform_user_cleanup_jobs IS
        'Identity-owned User erasure control plane; foreign module cleanup remains owner-local';
END
$user_cleanup_control_plane$;
