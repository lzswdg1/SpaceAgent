DO $runtime_coordination$
BEGIN
    IF to_regclass('public.platform_agent_runs') IS NULL
            OR to_regclass('public.platform_run_events') IS NULL THEN
        RETURN;
    END IF;

    CREATE TABLE platform_run_worker_leases (
        agent_run_id VARCHAR(36) PRIMARY KEY,
        lease_token UUID NOT NULL UNIQUE,
        lease_owner VARCHAR(160) NOT NULL,
        fencing_token BIGINT NOT NULL,
        lease_until TIMESTAMPTZ NOT NULL,
        revision BIGINT NOT NULL,
        acquired_at TIMESTAMPTZ NOT NULL,
        heartbeat_at TIMESTAMPTZ NOT NULL,
        released_at TIMESTAMPTZ,
        CONSTRAINT fk_platform_run_worker_lease_run
            FOREIGN KEY (agent_run_id) REFERENCES platform_agent_runs(id) ON DELETE CASCADE,
        CONSTRAINT ck_platform_run_worker_lease_fence
            CHECK (fencing_token > 0 AND revision > 0)
    );

    CREATE INDEX idx_platform_run_worker_lease_expiry
        ON platform_run_worker_leases(lease_until)
        WHERE released_at IS NULL;

    CREATE TABLE platform_runtime_continuations (
        id UUID PRIMARY KEY,
        agent_run_id VARCHAR(36) NOT NULL,
        continuation_type VARCHAR(48) NOT NULL,
        deduplication_key VARCHAR(200) NOT NULL,
        payload JSONB NOT NULL DEFAULT '{}'::JSONB,
        state VARCHAR(24) NOT NULL,
        available_at TIMESTAMPTZ NOT NULL,
        attempt INTEGER NOT NULL DEFAULT 0,
        max_attempts INTEGER NOT NULL,
        claim_token UUID,
        claim_owner VARCHAR(160),
        fencing_token BIGINT,
        lease_until TIMESTAMPTZ,
        revision BIGINT NOT NULL DEFAULT 0,
        last_error TEXT,
        created_at TIMESTAMPTZ NOT NULL,
        updated_at TIMESTAMPTZ NOT NULL,
        completed_at TIMESTAMPTZ,
        CONSTRAINT fk_platform_runtime_continuation_run
            FOREIGN KEY (agent_run_id) REFERENCES platform_agent_runs(id) ON DELETE CASCADE,
        CONSTRAINT uk_platform_runtime_continuation_dedup
            UNIQUE (agent_run_id, deduplication_key),
        CONSTRAINT ck_platform_runtime_continuation_type
            CHECK (continuation_type IN ('RESUME_RUN')),
        CONSTRAINT ck_platform_runtime_continuation_state
            CHECK (state IN ('PENDING', 'CLAIMED', 'COMPLETED', 'FAILED', 'CANCELLED')),
        CONSTRAINT ck_platform_runtime_continuation_attempt
            CHECK (attempt >= 0 AND max_attempts > 0 AND attempt <= max_attempts),
        CONSTRAINT ck_platform_runtime_continuation_revision
            CHECK (revision >= 0),
        CONSTRAINT ck_platform_runtime_continuation_claim
            CHECK (
                (state = 'CLAIMED' AND claim_token IS NOT NULL
                    AND claim_owner IS NOT NULL AND fencing_token IS NOT NULL
                    AND lease_until IS NOT NULL)
                OR
                (state <> 'CLAIMED' AND claim_token IS NULL
                    AND claim_owner IS NULL AND fencing_token IS NULL
                    AND lease_until IS NULL)
            ),
        CONSTRAINT ck_platform_runtime_continuation_completion
            CHECK (
                (state IN ('COMPLETED', 'FAILED', 'CANCELLED') AND completed_at IS NOT NULL)
                OR
                (state IN ('PENDING', 'CLAIMED') AND completed_at IS NULL)
            )
    );

    CREATE INDEX idx_platform_runtime_continuation_pending
        ON platform_runtime_continuations(available_at, created_at)
        WHERE state = 'PENDING';
    CREATE INDEX idx_platform_runtime_continuation_run
        ON platform_runtime_continuations(agent_run_id, created_at);

    ALTER TABLE platform_run_events
        DROP CONSTRAINT IF EXISTS ck_platform_run_event_type;
    ALTER TABLE platform_run_events
        ADD CONSTRAINT ck_platform_run_event_type CHECK (
            event_type IN (
                'RUN_CREATED', 'RUN_STATE_CHANGED', 'STEP_STARTED',
                'STEP_COMPLETED', 'STEP_FAILED', 'CHECKPOINT_CREATED',
                'CURSOR_ADVANCED', 'ORCHESTRATION_COMMAND_ACCEPTED',
                'WORKER_LEASE_ACQUIRED', 'WORKER_LEASE_RELEASED',
                'CONTINUATION_ENQUEUED', 'CONTINUATION_CLAIMED',
                'CONTINUATION_COMPLETED', 'CONTINUATION_FAILED'));
END
$runtime_coordination$;
