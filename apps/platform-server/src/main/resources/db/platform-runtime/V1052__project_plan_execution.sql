DO $project_plan_execution$
BEGIN
    IF to_regclass('public.platform_task_plans') IS NULL
            OR to_regclass('public.platform_projects') IS NULL
            OR to_regclass('public.platform_project_directories') IS NULL
            OR to_regclass('public.platform_source_repositories') IS NULL
            OR to_regclass('public.platform_tasks') IS NULL
            OR to_regclass('public.platform_conversations') IS NULL
            OR to_regclass('public.platform_project_coding_jobs') IS NULL
            OR to_regclass('public.platform_agent_definitions') IS NULL
            OR to_regclass('public.platform_agent_versions') IS NULL THEN
        RETURN;
    END IF;

    CREATE TABLE platform_project_plan_executions (
        id UUID PRIMARY KEY,
        tenant_id VARCHAR(36) NOT NULL,
        owner_id VARCHAR(36) NOT NULL,
        project_id UUID NOT NULL,
        project_directory_id UUID NOT NULL,
        conversation_id VARCHAR(36) NOT NULL,
        source_repository_id UUID NOT NULL,
        root_task_id UUID NOT NULL,
        task_plan_id UUID NOT NULL,
        agent_id VARCHAR(36) NOT NULL,
        agent_version_id UUID NOT NULL,
        reviewer_agent_version_id UUID NOT NULL,
        base_ref VARCHAR(240) NOT NULL,
        idempotency_hash VARCHAR(71) NOT NULL,
        input_hash VARCHAR(71) NOT NULL,
        state VARCHAR(24) NOT NULL,
        safe_error_code VARCHAR(120),
        attempt INTEGER NOT NULL DEFAULT 0,
        revision BIGINT NOT NULL DEFAULT 1,
        created_at TIMESTAMPTZ NOT NULL,
        started_at TIMESTAMPTZ,
        updated_at TIMESTAMPTZ NOT NULL,
        completed_at TIMESTAMPTZ,
        CONSTRAINT uk_project_plan_execution_plan UNIQUE(task_plan_id),
        CONSTRAINT ck_project_plan_execution_state CHECK(
            state IN ('READY', 'RUNNING', 'COMPLETED', 'FAILED', 'BLOCKED')
        ),
        CONSTRAINT ck_project_plan_execution_error CHECK(
            state NOT IN ('FAILED', 'BLOCKED') OR safe_error_code IS NOT NULL
        ),
        CONSTRAINT ck_project_plan_execution_hashes CHECK(
            idempotency_hash ~ '^sha256:[0-9a-f]{64}$'
            AND input_hash ~ '^sha256:[0-9a-f]{64}$'
        ),
        CONSTRAINT ck_project_plan_execution_values CHECK(
            attempt >= 0 AND revision > 0
        ),
        CONSTRAINT fk_platform_plan_execution_project
            FOREIGN KEY (project_id, tenant_id)
            REFERENCES platform_projects(id, tenant_id),
        CONSTRAINT fk_platform_plan_execution_directory
            FOREIGN KEY (project_directory_id, project_id, tenant_id)
            REFERENCES platform_project_directories(id, project_id, tenant_id),
        CONSTRAINT fk_platform_plan_execution_repository
            FOREIGN KEY (source_repository_id, project_id, tenant_id)
            REFERENCES platform_source_repositories(id, project_id, tenant_id),
        CONSTRAINT fk_platform_plan_execution_root
            FOREIGN KEY (root_task_id, project_id)
            REFERENCES platform_tasks(id, project_id),
        CONSTRAINT fk_platform_plan_execution_plan
            FOREIGN KEY (task_plan_id, project_id, root_task_id)
            REFERENCES platform_task_plans(id, project_id, root_task_id),
        CONSTRAINT fk_platform_plan_execution_conversation
            FOREIGN KEY (conversation_id)
            REFERENCES platform_conversations(id),
        CONSTRAINT fk_platform_plan_execution_agent
            FOREIGN KEY (agent_id)
            REFERENCES platform_agent_definitions(id),
        CONSTRAINT fk_platform_plan_execution_agent_version
            FOREIGN KEY (agent_version_id)
            REFERENCES platform_agent_versions(id),
        CONSTRAINT fk_platform_plan_execution_reviewer_version
            FOREIGN KEY (reviewer_agent_version_id)
            REFERENCES platform_agent_versions(id),
        CONSTRAINT ck_platform_plan_execution_terminal CHECK(
            (state NOT IN ('COMPLETED', 'FAILED', 'BLOCKED') AND completed_at IS NULL)
            OR (state IN ('COMPLETED', 'FAILED', 'BLOCKED') AND completed_at IS NOT NULL)
        )
    );

    CREATE INDEX idx_project_plan_execution_project
        ON platform_project_plan_executions(project_id, owner_id, created_at DESC, id DESC);
    CREATE INDEX idx_project_plan_execution_active
        ON platform_project_plan_executions(project_id, owner_id, state, updated_at DESC, created_at DESC, id DESC)
        WHERE state IN ('READY', 'RUNNING');

    ALTER TABLE platform_project_coding_jobs
        ADD COLUMN execution_id UUID;
    ALTER TABLE platform_project_coding_jobs
        ADD CONSTRAINT fk_project_coding_job_execution
            FOREIGN KEY (execution_id)
            REFERENCES platform_project_plan_executions(id);
    CREATE INDEX idx_project_coding_job_execution
        ON platform_project_coding_jobs(execution_id, state, created_at DESC)
        WHERE execution_id IS NOT NULL;
END
$project_plan_execution$;
