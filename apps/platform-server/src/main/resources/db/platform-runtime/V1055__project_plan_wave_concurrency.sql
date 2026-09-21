DO $project_plan_wave_concurrency$
BEGIN
    IF to_regclass('public.platform_project_plan_executions') IS NULL
            OR to_regclass('public.platform_plan_steps') IS NULL THEN
        RETURN;
    END IF;

    ALTER TABLE platform_project_plan_executions
        ADD CONSTRAINT uk_project_plan_execution_id_plan UNIQUE(id, task_plan_id);

    CREATE TABLE platform_project_plan_wave_concurrency (
        execution_id UUID PRIMARY KEY,
        tenant_id VARCHAR(36) NOT NULL,
        owner_id VARCHAR(36) NOT NULL,
        project_id UUID NOT NULL,
        task_plan_id UUID NOT NULL,
        max_parallelism INTEGER NOT NULL,
        active_claims INTEGER NOT NULL DEFAULT 0,
        revision BIGINT NOT NULL DEFAULT 1,
        created_at TIMESTAMPTZ NOT NULL,
        updated_at TIMESTAMPTZ NOT NULL,
        CONSTRAINT uk_project_plan_wave_execution_plan UNIQUE(execution_id, task_plan_id),
        CONSTRAINT ck_project_plan_wave_concurrency_values
            CHECK(max_parallelism > 0 AND active_claims >= 0 AND active_claims <= max_parallelism AND revision > 0),
        CONSTRAINT fk_project_plan_wave_execution
            FOREIGN KEY(execution_id) REFERENCES platform_project_plan_executions(id),
        CONSTRAINT fk_project_plan_wave_execution_plan
            FOREIGN KEY(execution_id, task_plan_id)
            REFERENCES platform_project_plan_executions(id, task_plan_id)
    );

    CREATE TABLE platform_project_plan_wave_claims (
        execution_id UUID NOT NULL,
        task_plan_id UUID NOT NULL,
        plan_step_id UUID NOT NULL,
        tenant_id VARCHAR(36) NOT NULL,
        owner_id VARCHAR(36) NOT NULL,
        claim_owner VARCHAR(160) NOT NULL,
        claim_token UUID NOT NULL,
        fencing_token BIGINT NOT NULL DEFAULT 1,
        lease_until TIMESTAMPTZ NOT NULL,
        state VARCHAR(16) NOT NULL,
        revision BIGINT NOT NULL DEFAULT 1,
        created_at TIMESTAMPTZ NOT NULL,
        updated_at TIMESTAMPTZ NOT NULL,
        released_at TIMESTAMPTZ,
        PRIMARY KEY(execution_id, plan_step_id),
        CONSTRAINT ck_project_plan_wave_claim_values
            CHECK(fencing_token > 0 AND revision > 0 AND state IN ('CLAIMED','RELEASED')
                AND ((state='CLAIMED' AND released_at IS NULL) OR state='RELEASED')),
        CONSTRAINT fk_project_plan_wave_claim_budget
            FOREIGN KEY(execution_id, task_plan_id)
            REFERENCES platform_project_plan_wave_concurrency(execution_id, task_plan_id),
        CONSTRAINT fk_project_plan_wave_claim_step
            FOREIGN KEY(task_plan_id, plan_step_id) REFERENCES platform_plan_steps(task_plan_id, id)
    );
    CREATE INDEX idx_project_plan_wave_claim_active
        ON platform_project_plan_wave_claims(execution_id, lease_until, plan_step_id)
        WHERE state='CLAIMED';
END
$project_plan_wave_concurrency$;
