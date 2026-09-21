DO $project_coding_jobs$
BEGIN
    IF to_regclass('public.platform_agent_runs') IS NULL
            OR to_regclass('public.platform_project_directories') IS NULL THEN RETURN; END IF;

    CREATE TABLE platform_project_coding_jobs (
        id UUID PRIMARY KEY,
        tenant_id VARCHAR(36) NOT NULL,
        owner_id VARCHAR(36) NOT NULL,
        project_id UUID NOT NULL,
        project_directory_id UUID NOT NULL,
        conversation_id VARCHAR(36) NOT NULL,
        source_repository_id UUID NOT NULL,
        root_task_id UUID NOT NULL,
        task_id UUID NOT NULL,
        task_plan_id UUID NOT NULL,
        plan_step_id UUID NOT NULL,
        agent_id VARCHAR(36) NOT NULL,
        agent_version_id UUID NOT NULL,
        reviewer_agent_version_id UUID NOT NULL,
        base_ref VARCHAR(240) NOT NULL,
        idempotency_hash VARCHAR(71) NOT NULL,
        input_hash VARCHAR(71) NOT NULL,
        state VARCHAR(24) NOT NULL,
        workspace_id UUID,
        coding_run_id VARCHAR(36),
        reviewer_run_id VARCHAR(36),
        iteration INTEGER NOT NULL DEFAULT 0,
        review_round INTEGER NOT NULL DEFAULT 0,
        context_json JSONB,
        pending_tool_json JSONB,
        pending_approval_id UUID,
        patch_artifact_id UUID,
        commit_artifact_id UUID,
        review_id UUID,
        source_merge_id UUID,
        safe_error_code VARCHAR(120),
        attempt INTEGER NOT NULL DEFAULT 0,
        claim_owner VARCHAR(160),
        claim_token UUID,
        fencing_token BIGINT NOT NULL DEFAULT 0,
        lease_until TIMESTAMPTZ,
        revision BIGINT NOT NULL DEFAULT 1,
        created_at TIMESTAMPTZ NOT NULL,
        started_at TIMESTAMPTZ,
        updated_at TIMESTAMPTZ NOT NULL,
        completed_at TIMESTAMPTZ,
        CONSTRAINT uk_project_coding_job_idempotency UNIQUE(tenant_id,owner_id,idempotency_hash),
        CONSTRAINT ck_project_coding_job_state CHECK(state IN(
            'PENDING','RUNNING','WAITING_APPROVAL','COMPLETED','FAILED','BLOCKED')),
        CONSTRAINT ck_project_coding_job_hash CHECK(
            idempotency_hash ~ '^sha256:[0-9a-f]{64}$' AND input_hash ~ '^sha256:[0-9a-f]{64}$'),
        CONSTRAINT ck_project_coding_job_claim CHECK((state='RUNNING') =
            (claim_owner IS NOT NULL AND claim_token IS NOT NULL AND lease_until IS NOT NULL)),
        CONSTRAINT ck_project_coding_job_approval CHECK(
            state<>'WAITING_APPROVAL' OR (pending_tool_json IS NOT NULL AND pending_approval_id IS NOT NULL)),
        CONSTRAINT ck_project_coding_job_terminal CHECK(
            (state NOT IN('COMPLETED','FAILED','BLOCKED') AND completed_at IS NULL)
            OR (state IN('COMPLETED','FAILED','BLOCKED') AND completed_at IS NOT NULL)),
        CONSTRAINT ck_project_coding_job_error CHECK(
            state NOT IN('FAILED','BLOCKED') OR safe_error_code IS NOT NULL),
        CONSTRAINT ck_project_coding_job_values CHECK(iteration>=0 AND review_round>=0
            AND attempt>=0 AND fencing_token>=0 AND revision>0
            AND octet_length(COALESCE(context_json::text,''))<=1000000
            AND octet_length(COALESCE(pending_tool_json::text,''))<=1000000),
        CONSTRAINT fk_project_coding_project FOREIGN KEY(project_id,tenant_id)
            REFERENCES platform_projects(id,tenant_id),
        CONSTRAINT fk_project_coding_directory FOREIGN KEY(project_directory_id,project_id,tenant_id)
            REFERENCES platform_project_directories(id,project_id,tenant_id),
        CONSTRAINT fk_project_coding_source FOREIGN KEY(source_repository_id,project_id,tenant_id)
            REFERENCES platform_source_repositories(id,project_id,tenant_id),
        CONSTRAINT fk_project_coding_root FOREIGN KEY(root_task_id,project_id)
            REFERENCES platform_tasks(id,project_id),
        CONSTRAINT fk_project_coding_task FOREIGN KEY(task_id,project_id)
            REFERENCES platform_tasks(id,project_id),
        CONSTRAINT fk_project_coding_plan FOREIGN KEY(task_plan_id,project_id,root_task_id)
            REFERENCES platform_task_plans(id,project_id,root_task_id),
        CONSTRAINT fk_project_coding_step FOREIGN KEY(task_plan_id,plan_step_id,task_id)
            REFERENCES platform_plan_steps(task_plan_id,id,child_task_id),
        CONSTRAINT fk_project_coding_conversation FOREIGN KEY(conversation_id)
            REFERENCES platform_conversations(id),
        CONSTRAINT fk_project_coding_agent FOREIGN KEY(agent_id)
            REFERENCES platform_agent_definitions(id),
        CONSTRAINT fk_project_coding_version FOREIGN KEY(agent_version_id)
            REFERENCES platform_agent_versions(id),
        CONSTRAINT fk_project_coding_reviewer_version FOREIGN KEY(reviewer_agent_version_id)
            REFERENCES platform_agent_versions(id),
        CONSTRAINT fk_project_coding_workspace FOREIGN KEY(workspace_id)
            REFERENCES platform_workspaces(id),
        CONSTRAINT fk_project_coding_run FOREIGN KEY(coding_run_id)
            REFERENCES platform_agent_runs(id),
        CONSTRAINT fk_project_coding_reviewer_run FOREIGN KEY(reviewer_run_id)
            REFERENCES platform_agent_runs(id),
        CONSTRAINT fk_project_coding_approval FOREIGN KEY(pending_approval_id)
            REFERENCES platform_approval_requests(id) ON DELETE SET NULL,
        CONSTRAINT fk_project_coding_patch FOREIGN KEY(patch_artifact_id)
            REFERENCES platform_artifacts(id) ON DELETE SET NULL,
        CONSTRAINT fk_project_coding_commit FOREIGN KEY(commit_artifact_id)
            REFERENCES platform_artifacts(id) ON DELETE SET NULL,
        CONSTRAINT fk_project_coding_review FOREIGN KEY(review_id)
            REFERENCES platform_agent_reviews(id) ON DELETE SET NULL,
        CONSTRAINT fk_project_coding_merge FOREIGN KEY(source_merge_id)
            REFERENCES platform_source_merge_jobs(id)
    );
    CREATE UNIQUE INDEX uk_project_coding_job_active_step
        ON platform_project_coding_jobs(plan_step_id)
        WHERE state IN('PENDING','RUNNING','WAITING_APPROVAL');
    CREATE INDEX idx_project_coding_job_claim ON platform_project_coding_jobs(created_at,id)
        WHERE state IN('PENDING','RUNNING');
    CREATE INDEX idx_project_coding_job_step ON platform_project_coding_jobs(plan_step_id,created_at DESC,id DESC);
END
$project_coding_jobs$;
