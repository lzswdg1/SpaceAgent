DO $project_run_handoffs$
BEGIN
    IF to_regclass('public.platform_project_coding_jobs') IS NULL THEN RETURN; END IF;

    ALTER TABLE platform_project_coding_jobs DROP CONSTRAINT ck_project_coding_job_state;
    ALTER TABLE platform_project_coding_jobs ADD CONSTRAINT ck_project_coding_job_state CHECK(state IN(
        'PENDING','RUNNING','WAITING_APPROVAL','HANDED_OFF','COMPLETED','FAILED','BLOCKED'));
    ALTER TABLE platform_project_coding_jobs DROP CONSTRAINT ck_project_coding_job_terminal;
    ALTER TABLE platform_project_coding_jobs ADD CONSTRAINT ck_project_coding_job_terminal CHECK(
        (state NOT IN('HANDED_OFF','COMPLETED','FAILED','BLOCKED') AND completed_at IS NULL)
        OR (state IN('HANDED_OFF','COMPLETED','FAILED','BLOCKED') AND completed_at IS NOT NULL));

    CREATE TABLE platform_project_run_handoffs (
        id UUID PRIMARY KEY,
        tenant_id VARCHAR(36) NOT NULL,
        owner_id VARCHAR(36) NOT NULL,
        project_id UUID NOT NULL,
        project_directory_id UUID NOT NULL,
        source_repository_id UUID NOT NULL,
        root_task_id UUID NOT NULL,
        task_id UUID NOT NULL,
        task_plan_id UUID NOT NULL,
        plan_step_id UUID NOT NULL,
        base_ref VARCHAR(240) NOT NULL,
        source_coding_job_id UUID NOT NULL,
        source_agent_run_id VARCHAR(36) NOT NULL,
        workspace_id UUID NOT NULL,
        recovery_snapshot_id UUID NOT NULL,
        recovery_snapshot_hash VARCHAR(71) NOT NULL,
        target_conversation_id VARCHAR(36) NOT NULL,
        target_agent_id VARCHAR(36) NOT NULL,
        target_agent_version_id UUID NOT NULL,
        reviewer_agent_version_id UUID NOT NULL,
        target_coding_job_id UUID NOT NULL,
        target_agent_run_id VARCHAR(36),
        idempotency_hash VARCHAR(71) NOT NULL,
        input_hash VARCHAR(71) NOT NULL,
        state VARCHAR(32) NOT NULL,
        memory_key VARCHAR(240),
        safe_error_code VARCHAR(120),
        attempt INTEGER NOT NULL DEFAULT 0,
        claim_owner VARCHAR(160),
        claim_token UUID,
        fencing_token BIGINT NOT NULL DEFAULT 0,
        lease_until TIMESTAMPTZ,
        revision BIGINT NOT NULL DEFAULT 1,
        created_at TIMESTAMPTZ NOT NULL,
        updated_at TIMESTAMPTZ NOT NULL,
        completed_at TIMESTAMPTZ,
        CONSTRAINT uk_project_run_handoff_idempotency UNIQUE(tenant_id,owner_id,idempotency_hash),
        CONSTRAINT uk_project_run_handoff_source_job UNIQUE(source_coding_job_id),
        CONSTRAINT uk_project_run_handoff_target_job UNIQUE(target_coding_job_id),
        CONSTRAINT ck_project_run_handoff_hashes CHECK(
            recovery_snapshot_hash ~ '^sha256:[0-9a-f]{64}$'
            AND idempotency_hash ~ '^sha256:[0-9a-f]{64}$'
            AND input_hash ~ '^sha256:[0-9a-f]{64}$'),
        CONSTRAINT ck_project_run_handoff_state CHECK(state IN(
            'PENDING','ACTIVE','READY_TO_FINALIZE','FINALIZING','COMPLETED','BLOCKED')),
        CONSTRAINT ck_project_run_handoff_claim CHECK((state='FINALIZING') =
            (claim_owner IS NOT NULL AND claim_token IS NOT NULL AND lease_until IS NOT NULL)),
        CONSTRAINT ck_project_run_handoff_terminal CHECK(
            (state NOT IN('COMPLETED','BLOCKED') AND completed_at IS NULL)
            OR (state IN('COMPLETED','BLOCKED') AND completed_at IS NOT NULL)),
        CONSTRAINT ck_project_run_handoff_values CHECK(
            attempt>=0 AND fencing_token>=0 AND revision>0),
        CONSTRAINT fk_project_run_handoff_project FOREIGN KEY(project_id,tenant_id)
            REFERENCES platform_projects(id,tenant_id),
        CONSTRAINT fk_project_run_handoff_directory FOREIGN KEY(project_directory_id,project_id,tenant_id)
            REFERENCES platform_project_directories(id,project_id,tenant_id),
        CONSTRAINT fk_project_run_handoff_source FOREIGN KEY(source_repository_id,project_id,tenant_id)
            REFERENCES platform_source_repositories(id,project_id,tenant_id),
        CONSTRAINT fk_project_run_handoff_root FOREIGN KEY(root_task_id,project_id)
            REFERENCES platform_tasks(id,project_id),
        CONSTRAINT fk_project_run_handoff_task FOREIGN KEY(task_id,project_id)
            REFERENCES platform_tasks(id,project_id),
        CONSTRAINT fk_project_run_handoff_plan FOREIGN KEY(task_plan_id,project_id,root_task_id)
            REFERENCES platform_task_plans(id,project_id,root_task_id),
        CONSTRAINT fk_project_run_handoff_step FOREIGN KEY(task_plan_id,plan_step_id,task_id)
            REFERENCES platform_plan_steps(task_plan_id,id,child_task_id),
        CONSTRAINT fk_project_run_handoff_source_job FOREIGN KEY(source_coding_job_id)
            REFERENCES platform_project_coding_jobs(id),
        CONSTRAINT fk_project_run_handoff_target_job FOREIGN KEY(target_coding_job_id)
            REFERENCES platform_project_coding_jobs(id),
        CONSTRAINT fk_project_run_handoff_source_run FOREIGN KEY(source_agent_run_id)
            REFERENCES platform_agent_runs(id),
        CONSTRAINT fk_project_run_handoff_target_run FOREIGN KEY(target_agent_run_id)
            REFERENCES platform_agent_runs(id),
        CONSTRAINT fk_project_run_handoff_workspace FOREIGN KEY(workspace_id)
            REFERENCES platform_workspaces(id),
        CONSTRAINT fk_project_run_handoff_snapshot FOREIGN KEY(recovery_snapshot_id)
            REFERENCES platform_project_execution_context_snapshots(id),
        CONSTRAINT fk_project_run_handoff_conversation FOREIGN KEY(target_conversation_id)
            REFERENCES platform_conversations(id),
        CONSTRAINT fk_project_run_handoff_agent FOREIGN KEY(target_agent_id)
            REFERENCES platform_agent_definitions(id),
        CONSTRAINT fk_project_run_handoff_agent_version FOREIGN KEY(target_agent_version_id)
            REFERENCES platform_agent_versions(id),
        CONSTRAINT fk_project_run_handoff_reviewer_version FOREIGN KEY(reviewer_agent_version_id)
            REFERENCES platform_agent_versions(id)
    );
    CREATE INDEX idx_project_run_handoff_project
        ON platform_project_run_handoffs(project_id,owner_id,created_at DESC,id DESC);
    CREATE INDEX idx_project_run_handoff_finalize
        ON platform_project_run_handoffs(created_at,id)
        WHERE state IN('READY_TO_FINALIZE','FINALIZING');
END
$project_run_handoffs$;
