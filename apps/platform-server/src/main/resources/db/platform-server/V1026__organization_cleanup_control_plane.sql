DO $organization_cleanup_control_plane$
BEGIN
    IF to_regclass('public.platform_tenants') IS NULL THEN
        RETURN;
    END IF;

    CREATE TABLE platform_organization_cleanup_jobs (
        organization_id       VARCHAR(36) PRIMARY KEY,
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
        CONSTRAINT fk_platform_organization_cleanup_job
            FOREIGN KEY (organization_id) REFERENCES platform_tenants(id),
        CONSTRAINT ck_platform_organization_cleanup_job_state
            CHECK (state IN ('PENDING', 'CLAIMED', 'RETRY', 'BLOCKED', 'COMPLETED')),
        CONSTRAINT ck_platform_organization_cleanup_job_attempt
            CHECK (attempt >= 0 AND max_attempts BETWEEN 1 AND 100
                AND attempt <= max_attempts),
        CONSTRAINT ck_platform_organization_cleanup_job_revision
            CHECK (fencing_token >= 0 AND revision >= 0),
        CONSTRAINT ck_platform_organization_cleanup_job_claim CHECK (
            (state = 'CLAIMED' AND lease_owner IS NOT NULL AND lease_token IS NOT NULL
                AND lease_until IS NOT NULL AND fencing_token > 0)
            OR
            (state <> 'CLAIMED' AND lease_owner IS NULL AND lease_token IS NULL
                AND lease_until IS NULL)
        ),
        CONSTRAINT ck_platform_organization_cleanup_job_completion CHECK (
            (state = 'COMPLETED' AND completed_at IS NOT NULL)
            OR (state <> 'COMPLETED' AND completed_at IS NULL)
        ),
        CONSTRAINT ck_platform_organization_cleanup_job_error CHECK (
            (last_error_code IS NULL AND last_error_summary IS NULL)
            OR (last_error_code ~ '^[A-Z][A-Z0-9_]{2,63}$'
                AND last_error_summary IS NOT NULL)
        )
    );

    CREATE INDEX idx_platform_organization_cleanup_job_due
        ON platform_organization_cleanup_jobs (next_attempt_at, created_at, organization_id)
        WHERE state IN ('PENDING', 'RETRY');

    CREATE INDEX idx_platform_organization_cleanup_job_expired_claim
        ON platform_organization_cleanup_jobs (lease_until, organization_id)
        WHERE state = 'CLAIMED';

    CREATE TABLE platform_organization_cleanup_steps (
        organization_id    VARCHAR(36) NOT NULL,
        step_key           VARCHAR(64) NOT NULL,
        step_sequence      INTEGER NOT NULL,
        state              VARCHAR(20) NOT NULL,
        attempt            INTEGER NOT NULL DEFAULT 0,
        last_error_code    VARCHAR(64),
        last_error_summary VARCHAR(500),
        created_at         TIMESTAMPTZ NOT NULL,
        updated_at         TIMESTAMPTZ NOT NULL,
        completed_at       TIMESTAMPTZ,
        PRIMARY KEY (organization_id, step_key),
        CONSTRAINT fk_platform_organization_cleanup_step_job
            FOREIGN KEY (organization_id)
            REFERENCES platform_organization_cleanup_jobs(organization_id) ON DELETE CASCADE,
        CONSTRAINT uk_platform_organization_cleanup_step_sequence
            UNIQUE (organization_id, step_sequence),
        CONSTRAINT ck_platform_organization_cleanup_step_key CHECK (step_key IN (
            'AUTOMATION_FREEZE_PURGE', 'RUNTIME_QUIESCE', 'ARTIFACT_PURGE',
            'RUNTIME_PURGE', 'CONVERSATION_PURGE', 'PROJECT_TASK_MEMORY_PURGE',
            'PROJECT_EXTERNAL_AND_DATABASE_PURGE', 'AGENT_PURGE', 'INFERENCE_PURGE',
            'GOVERNANCE_PURGE', 'IDENTITY_FINALIZE')),
        CONSTRAINT ck_platform_organization_cleanup_step_state
            CHECK (state IN ('PENDING', 'COMPLETED')),
        CONSTRAINT ck_platform_organization_cleanup_step_attempt CHECK (attempt >= 0),
        CONSTRAINT ck_platform_organization_cleanup_step_completion CHECK (
            (state = 'COMPLETED' AND completed_at IS NOT NULL)
            OR (state = 'PENDING' AND completed_at IS NULL)
        ),
        CONSTRAINT ck_platform_organization_cleanup_step_error CHECK (
            (last_error_code IS NULL AND last_error_summary IS NULL)
            OR (last_error_code ~ '^[A-Z][A-Z0-9_]{2,63}$'
                AND last_error_summary IS NOT NULL)
        )
    );

    CREATE INDEX idx_platform_organization_cleanup_step_pending
        ON platform_organization_cleanup_steps (organization_id, step_sequence)
        WHERE state = 'PENDING';

    COMMENT ON TABLE platform_organization_cleanup_jobs IS
        'Identity-owned durable control plane; it never owns foreign module purge SQL';
END
$organization_cleanup_control_plane$;
