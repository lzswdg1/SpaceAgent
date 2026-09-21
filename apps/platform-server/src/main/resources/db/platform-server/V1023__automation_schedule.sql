DO $automation_schedule$
BEGIN
    IF to_regclass('public.platform_tenants') IS NULL
            OR to_regclass('public.platform_users') IS NULL
            OR to_regclass('public.platform_agent_definitions') IS NULL
            OR to_regclass('public.platform_agent_versions') IS NULL
            OR to_regclass('public.platform_conversations') IS NULL
            OR to_regclass('public.platform_agent_runs') IS NULL
            OR to_regclass('public.platform_runtime_continuations') IS NULL
            OR to_regclass('public.platform_approval_requests') IS NULL THEN
        RETURN;
    END IF;

    CREATE TABLE platform_automation_schedules (
        id UUID PRIMARY KEY,
        tenant_id VARCHAR(64) NOT NULL,
        owner_id VARCHAR(36) NOT NULL,
        agent_id VARCHAR(64) NOT NULL,
        description VARCHAR(200) NOT NULL,
        prompt TEXT NOT NULL,
        schedule_type VARCHAR(20) NOT NULL,
        cron_expression VARCHAR(120),
        scheduled_at TIMESTAMPTZ,
        timezone VARCHAR(80) NOT NULL,
        state VARCHAR(20) NOT NULL,
        next_fire_at TIMESTAMPTZ,
        last_run_at TIMESTAMPTZ,
        last_run_status VARCHAR(32),
        last_error VARCHAR(2000),
        run_count BIGINT NOT NULL DEFAULT 0,
        max_retries INTEGER NOT NULL DEFAULT 1,
        revision BIGINT NOT NULL DEFAULT 1,
        created_at TIMESTAMPTZ NOT NULL,
        updated_at TIMESTAMPTZ NOT NULL,
        archived_at TIMESTAMPTZ,
        CONSTRAINT fk_platform_automation_schedule_tenant
            FOREIGN KEY (tenant_id) REFERENCES platform_tenants(id),
        CONSTRAINT fk_platform_automation_schedule_owner
            FOREIGN KEY (owner_id) REFERENCES platform_users(id),
        CONSTRAINT fk_platform_automation_schedule_agent
            FOREIGN KEY (agent_id) REFERENCES platform_agent_definitions(id),
        CONSTRAINT ck_platform_automation_schedule_type
            CHECK (schedule_type IN ('PERIODIC', 'ONE_TIME')),
        CONSTRAINT ck_platform_automation_schedule_state
            CHECK (state IN ('ACTIVE', 'PAUSED', 'COMPLETED', 'FAILED', 'ARCHIVED')),
        CONSTRAINT ck_platform_automation_schedule_shape CHECK (
            (schedule_type = 'PERIODIC' AND cron_expression IS NOT NULL
                AND scheduled_at IS NULL)
            OR (schedule_type = 'ONE_TIME' AND cron_expression IS NULL
                AND scheduled_at IS NOT NULL)),
        CONSTRAINT ck_platform_automation_schedule_active CHECK (
            state <> 'ACTIVE' OR next_fire_at IS NOT NULL),
        CONSTRAINT ck_platform_automation_schedule_archive CHECK (
            (state = 'ARCHIVED' AND archived_at IS NOT NULL)
            OR (state <> 'ARCHIVED' AND archived_at IS NULL)),
        CONSTRAINT ck_platform_automation_schedule_counters
            CHECK (run_count >= 0 AND max_retries BETWEEN 0 AND 10 AND revision > 0)
    );

    CREATE TABLE platform_automation_executions (
        id UUID PRIMARY KEY,
        schedule_id UUID NOT NULL,
        tenant_id VARCHAR(64) NOT NULL,
        owner_id VARCHAR(36) NOT NULL,
        agent_id VARCHAR(64) NOT NULL,
        agent_version_id UUID NOT NULL,
        fire_key VARCHAR(200) NOT NULL,
        scheduled_for TIMESTAMPTZ NOT NULL,
        trigger_type VARCHAR(20) NOT NULL,
        state VARCHAR(32) NOT NULL,
        operation_hash VARCHAR(71) NOT NULL,
        approval_id UUID,
        conversation_id VARCHAR(64),
        dispatch_run_id VARCHAR(64),
        continuation_id UUID,
        agent_run_id VARCHAR(64),
        started_at TIMESTAMPTZ,
        completed_at TIMESTAMPTZ,
        error VARCHAR(2000),
        input_tokens INTEGER NOT NULL DEFAULT 0,
        output_tokens INTEGER NOT NULL DEFAULT 0,
        revision BIGINT NOT NULL DEFAULT 1,
        created_at TIMESTAMPTZ NOT NULL,
        updated_at TIMESTAMPTZ NOT NULL,
        CONSTRAINT fk_platform_automation_execution_schedule
            FOREIGN KEY (schedule_id) REFERENCES platform_automation_schedules(id),
        CONSTRAINT fk_platform_automation_execution_tenant
            FOREIGN KEY (tenant_id) REFERENCES platform_tenants(id),
        CONSTRAINT fk_platform_automation_execution_owner
            FOREIGN KEY (owner_id) REFERENCES platform_users(id),
        CONSTRAINT fk_platform_automation_execution_agent
            FOREIGN KEY (agent_id) REFERENCES platform_agent_definitions(id),
        CONSTRAINT fk_platform_automation_execution_version
            FOREIGN KEY (agent_version_id) REFERENCES platform_agent_versions(id),
        CONSTRAINT fk_platform_automation_execution_approval
            FOREIGN KEY (approval_id) REFERENCES platform_approval_requests(id),
        CONSTRAINT fk_platform_automation_execution_conversation
            FOREIGN KEY (conversation_id) REFERENCES platform_conversations(id),
        CONSTRAINT fk_platform_automation_execution_dispatch_run
            FOREIGN KEY (dispatch_run_id) REFERENCES platform_agent_runs(id),
        CONSTRAINT fk_platform_automation_execution_continuation
            FOREIGN KEY (continuation_id) REFERENCES platform_runtime_continuations(id),
        CONSTRAINT fk_platform_automation_execution_agent_run
            FOREIGN KEY (agent_run_id) REFERENCES platform_agent_runs(id),
        CONSTRAINT uk_platform_automation_execution_fire UNIQUE (schedule_id, fire_key),
        CONSTRAINT ck_platform_automation_execution_trigger
            CHECK (trigger_type IN ('SCHEDULED', 'MANUAL')),
        CONSTRAINT ck_platform_automation_execution_state CHECK (state IN (
            'PENDING_DISPATCH', 'APPROVAL_REQUIRED', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED',
            'UNKNOWN', 'REJECTED', 'EXPIRED', 'CANCELLED')),
        CONSTRAINT ck_platform_automation_execution_hash
            CHECK (operation_hash ~ '^sha256:[0-9a-f]{64}$'),
        CONSTRAINT ck_platform_automation_execution_dispatch CHECK (
            (conversation_id IS NULL AND dispatch_run_id IS NULL AND continuation_id IS NULL)
            OR (conversation_id IS NOT NULL AND dispatch_run_id IS NOT NULL
                AND continuation_id IS NOT NULL)),
        CONSTRAINT ck_platform_automation_execution_dispatched_state CHECK (
            state NOT IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')
            OR (conversation_id IS NOT NULL AND dispatch_run_id IS NOT NULL
                AND continuation_id IS NOT NULL)),
        CONSTRAINT ck_platform_automation_execution_approval CHECK (
            state <> 'APPROVAL_REQUIRED' OR approval_id IS NOT NULL),
        CONSTRAINT ck_platform_automation_execution_completion CHECK (
            (state IN ('SUCCEEDED', 'FAILED', 'UNKNOWN', 'REJECTED', 'EXPIRED', 'CANCELLED')
                AND completed_at IS NOT NULL)
            OR (state IN ('PENDING_DISPATCH', 'APPROVAL_REQUIRED', 'QUEUED', 'RUNNING')
                AND completed_at IS NULL)),
        CONSTRAINT ck_platform_automation_execution_counters
            CHECK (input_tokens >= 0 AND output_tokens >= 0 AND revision > 0)
    );

    CREATE INDEX idx_platform_automation_schedule_due
        ON platform_automation_schedules(next_fire_at, created_at, id)
        WHERE state = 'ACTIVE';
    CREATE INDEX idx_platform_automation_schedule_owner_agent
        ON platform_automation_schedules(tenant_id, owner_id, agent_id, created_at);
    CREATE INDEX idx_platform_automation_execution_schedule
        ON platform_automation_executions(schedule_id, created_at DESC);
    CREATE INDEX idx_platform_automation_execution_approval
        ON platform_automation_executions(updated_at, id)
        WHERE state = 'APPROVAL_REQUIRED';
    CREATE INDEX idx_platform_automation_execution_running
        ON platform_automation_executions(started_at, id)
        WHERE state = 'RUNNING';

    COMMENT ON TABLE platform_automation_schedules IS
        'PostgreSQL-clock authoritative Automation schedules; no Redis/Bull truth';
    COMMENT ON TABLE platform_automation_executions IS
        'Immutable occurrences and at-most-once execution outcome ledger';
END
$automation_schedule$;
