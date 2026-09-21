DO $project_plan_merge_barrier$
BEGIN
    IF to_regclass('public.platform_project_plan_executions') IS NULL THEN
        RETURN;
    END IF;
    CREATE TABLE platform_project_plan_merge_barriers (
        execution_id UUID PRIMARY KEY,
        tenant_id VARCHAR(36) NOT NULL,
        owner_id VARCHAR(36) NOT NULL,
        project_id UUID NOT NULL,
        task_plan_id UUID NOT NULL,
        next_apply_index INTEGER NOT NULL DEFAULT 0,
        state VARCHAR(16) NOT NULL,
        revision BIGINT NOT NULL DEFAULT 1,
        created_at TIMESTAMPTZ NOT NULL,
        updated_at TIMESTAMPTZ NOT NULL,
        completed_at TIMESTAMPTZ,
        CONSTRAINT ck_project_plan_merge_barrier_values
            CHECK(next_apply_index >= 0 AND revision > 0 AND state IN ('ACTIVE','COMPLETED','BLOCKED')),
        CONSTRAINT fk_project_plan_merge_barrier_execution
            FOREIGN KEY(execution_id) REFERENCES platform_project_plan_executions(id),
        CONSTRAINT fk_project_plan_merge_barrier_execution_plan
            FOREIGN KEY(execution_id, task_plan_id)
            REFERENCES platform_project_plan_executions(id, task_plan_id)
    );
    CREATE TABLE platform_project_plan_merge_barrier_entries (
        execution_id UUID NOT NULL,
        apply_index INTEGER NOT NULL,
        plan_step_id UUID NOT NULL,
        source_merge_id UUID NOT NULL,
        state VARCHAR(16) NOT NULL,
        revision BIGINT NOT NULL DEFAULT 1,
        created_at TIMESTAMPTZ NOT NULL,
        updated_at TIMESTAMPTZ NOT NULL,
        completed_at TIMESTAMPTZ,
        PRIMARY KEY(execution_id, apply_index),
        CONSTRAINT uk_project_plan_merge_barrier_step UNIQUE(execution_id, plan_step_id),
        CONSTRAINT ck_project_plan_merge_barrier_entry_values
            CHECK(apply_index >= 0 AND revision > 0 AND state IN ('READY','APPLIED','BLOCKED')
                AND ((state='READY' AND completed_at IS NULL) OR state IN ('APPLIED','BLOCKED'))),
        CONSTRAINT fk_project_plan_merge_barrier_entry
            FOREIGN KEY(execution_id) REFERENCES platform_project_plan_merge_barriers(execution_id)
    );
END
$project_plan_merge_barrier$;
