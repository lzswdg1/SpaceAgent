DO $project_plan_step_assignment$
BEGIN
    IF to_regclass('public.platform_projects') IS NULL
            OR to_regclass('public.platform_task_plans') IS NULL
            OR to_regclass('public.platform_plan_steps') IS NULL
            OR to_regclass('public.platform_agent_definitions') IS NULL
            OR to_regclass('public.platform_agent_versions') IS NULL
            OR to_regclass('public.platform_model_pools') IS NULL THEN
        RETURN;
    END IF;

    CREATE TABLE platform_project_plan_step_assignments (
        id UUID PRIMARY KEY,
        tenant_id VARCHAR(36) NOT NULL,
        owner_id VARCHAR(36) NOT NULL,
        project_id UUID NOT NULL,
        task_plan_id UUID NOT NULL,
        plan_step_id UUID NOT NULL,
        revision BIGINT NOT NULL,
        source VARCHAR(24) NOT NULL,
        agent_id VARCHAR(36) NOT NULL,
        agent_version_id UUID NOT NULL,
        reviewer_agent_id VARCHAR(36) NOT NULL,
        reviewer_agent_version_id UUID NOT NULL,
        model_pool_id UUID NOT NULL,
        capability_hash VARCHAR(64) NOT NULL,
        configuration_hash VARCHAR(64) NOT NULL,
        assignment_hash VARCHAR(64) NOT NULL,
        assigned_at TIMESTAMPTZ NOT NULL,
        CONSTRAINT uk_project_plan_step_assignment_revision
            UNIQUE(task_plan_id, plan_step_id, revision),
        CONSTRAINT ck_project_plan_step_assignment_source
            CHECK(source IN ('PLAN_DEFAULT', 'STEP_OVERRIDE', 'HANDOFF')),
        CONSTRAINT ck_project_plan_step_assignment_values
            CHECK(revision > 0 AND agent_id <> reviewer_agent_id
                AND agent_version_id <> reviewer_agent_version_id
                AND capability_hash ~ '^[0-9a-f]{64}$'
                AND configuration_hash ~ '^[0-9a-f]{64}$'
                AND assignment_hash ~ '^[0-9a-f]{64}$'),
        CONSTRAINT fk_project_plan_step_assignment_project
            FOREIGN KEY(project_id, tenant_id) REFERENCES platform_projects(id, tenant_id),
        CONSTRAINT fk_project_plan_step_assignment_plan
            FOREIGN KEY(task_plan_id, project_id) REFERENCES platform_task_plans(id, project_id),
        CONSTRAINT fk_project_plan_step_assignment_step
            FOREIGN KEY(task_plan_id, plan_step_id) REFERENCES platform_plan_steps(task_plan_id, id),
        CONSTRAINT fk_project_plan_step_assignment_agent
            FOREIGN KEY(agent_id) REFERENCES platform_agent_definitions(id),
        CONSTRAINT fk_project_plan_step_assignment_agent_version
            FOREIGN KEY(agent_version_id) REFERENCES platform_agent_versions(id),
        CONSTRAINT fk_project_plan_step_assignment_reviewer_agent
            FOREIGN KEY(reviewer_agent_id) REFERENCES platform_agent_definitions(id),
        CONSTRAINT fk_project_plan_step_assignment_reviewer_version
            FOREIGN KEY(reviewer_agent_version_id) REFERENCES platform_agent_versions(id),
        CONSTRAINT fk_project_plan_step_assignment_pool
            FOREIGN KEY(model_pool_id) REFERENCES platform_model_pools(id)
    );

    CREATE INDEX idx_project_plan_step_assignment_scope
        ON platform_project_plan_step_assignments(
            project_id, owner_id, task_plan_id, plan_step_id, revision DESC, assigned_at DESC);
END
$project_plan_step_assignment$;
