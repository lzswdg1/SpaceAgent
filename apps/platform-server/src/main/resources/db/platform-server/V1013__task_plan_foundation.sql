-- Project-owned TaskPlan/PlanStep DAG and Conversation active Task foundation.
DO $task_plan_foundation$
BEGIN
    IF to_regclass('public.platform_projects') IS NULL
            OR to_regclass('public.platform_tasks') IS NULL
            OR to_regclass('public.platform_conversations') IS NULL
            OR to_regclass('public.platform_agent_versions') IS NULL
            OR to_regclass('public.platform_agent_definitions') IS NULL THEN
        RETURN;
    END IF;

    CREATE TABLE platform_task_plans (
        id                              UUID PRIMARY KEY,
        project_id                      UUID NOT NULL,
        root_task_id                    UUID NOT NULL,
        version_number                  INTEGER NOT NULL,
        status                          VARCHAR(32) NOT NULL,
        generated_by_agent_version_id   UUID,
        created_by                      VARCHAR(36) NOT NULL,
        approved_by                     VARCHAR(36),
        approved_at                     TIMESTAMPTZ,
        created_at                      TIMESTAMPTZ NOT NULL,
        updated_at                      TIMESTAMPTZ NOT NULL,
        CONSTRAINT uk_platform_task_plan_root_version
            UNIQUE (root_task_id, version_number),
        CONSTRAINT uk_platform_task_plan_id_project
            UNIQUE (id, project_id),
        CONSTRAINT uk_platform_task_plan_id_project_root
            UNIQUE (id, project_id, root_task_id),
        CONSTRAINT ck_platform_task_plan_version
            CHECK (version_number > 0),
        CONSTRAINT ck_platform_task_plan_status
            CHECK (status IN ('DRAFT', 'PROPOSED', 'APPROVED', 'ACTIVE',
                              'COMPLETED', 'CANCELLED')),
        CONSTRAINT ck_platform_task_plan_approval
            CHECK ((approved_by IS NULL) = (approved_at IS NULL)),
        CONSTRAINT fk_platform_task_plan_project
            FOREIGN KEY (project_id) REFERENCES platform_projects(id),
        CONSTRAINT fk_platform_task_plan_root
            FOREIGN KEY (project_id, root_task_id)
            REFERENCES platform_tasks(project_id, id),
        CONSTRAINT fk_platform_task_plan_generator
            FOREIGN KEY (generated_by_agent_version_id)
            REFERENCES platform_agent_versions(id)
    );

    CREATE UNIQUE INDEX uk_platform_task_plan_active_root
        ON platform_task_plans(root_task_id)
        WHERE status = 'ACTIVE';

    CREATE INDEX idx_platform_task_plan_root_created
        ON platform_task_plans(root_task_id, version_number DESC);

    CREATE TABLE platform_plan_steps (
        id                          UUID PRIMARY KEY,
        task_plan_id                UUID NOT NULL,
        project_id                  UUID NOT NULL,
        step_key                    VARCHAR(64) NOT NULL,
        sequence_number             INTEGER NOT NULL,
        child_task_id               UUID NOT NULL,
        required_capability         VARCHAR(120),
        preferred_agent_id          VARCHAR(36),
        expected_output             TEXT NOT NULL,
        acceptance_criteria_json    JSONB NOT NULL DEFAULT '[]'::JSONB,
        approval_required           BOOLEAN NOT NULL,
        state                       VARCHAR(32) NOT NULL,
        created_at                  TIMESTAMPTZ NOT NULL,
        updated_at                  TIMESTAMPTZ NOT NULL,
        CONSTRAINT uk_platform_plan_step_key UNIQUE (task_plan_id, step_key),
        CONSTRAINT uk_platform_plan_step_sequence UNIQUE (task_plan_id, sequence_number),
        CONSTRAINT uk_platform_plan_step_child UNIQUE (task_plan_id, child_task_id),
        CONSTRAINT uk_platform_plan_step_plan_id UNIQUE (task_plan_id, id),
        CONSTRAINT ck_platform_plan_step_sequence CHECK (sequence_number >= 0),
        CONSTRAINT ck_platform_plan_step_acceptance_array
            CHECK (jsonb_typeof(acceptance_criteria_json) = 'array'),
        CONSTRAINT ck_platform_plan_step_state
            CHECK (state IN ('PENDING', 'READY', 'IN_PROGRESS', 'BLOCKED',
                             'COMPLETED', 'CANCELLED')),
        CONSTRAINT fk_platform_plan_step_plan
            FOREIGN KEY (task_plan_id, project_id)
            REFERENCES platform_task_plans(id, project_id) ON DELETE CASCADE,
        CONSTRAINT fk_platform_plan_step_child_task
            FOREIGN KEY (project_id, child_task_id)
            REFERENCES platform_tasks(project_id, id),
        CONSTRAINT fk_platform_plan_step_preferred_agent
            FOREIGN KEY (preferred_agent_id)
            REFERENCES platform_agent_definitions(id)
    );

    CREATE TABLE platform_plan_step_dependencies (
        task_plan_id       UUID NOT NULL,
        step_id            UUID NOT NULL,
        depends_on_step_id UUID NOT NULL,
        PRIMARY KEY (task_plan_id, step_id, depends_on_step_id),
        CONSTRAINT ck_platform_plan_step_dependency_not_self
            CHECK (step_id <> depends_on_step_id),
        CONSTRAINT fk_platform_plan_step_dependency_step
            FOREIGN KEY (task_plan_id, step_id)
            REFERENCES platform_plan_steps(task_plan_id, id) ON DELETE CASCADE,
        CONSTRAINT fk_platform_plan_step_dependency_parent
            FOREIGN KEY (task_plan_id, depends_on_step_id)
            REFERENCES platform_plan_steps(task_plan_id, id) ON DELETE CASCADE
    );

    ALTER TABLE platform_tasks
        ADD COLUMN current_task_plan_id UUID;

    ALTER TABLE platform_tasks
        ADD CONSTRAINT fk_platform_task_current_plan
            FOREIGN KEY (current_task_plan_id, project_id, id)
            REFERENCES platform_task_plans(id, project_id, root_task_id);

    ALTER TABLE platform_conversations
        ADD COLUMN active_task_id UUID;

    ALTER TABLE platform_conversations
        ADD CONSTRAINT fk_platform_conversation_active_task
            FOREIGN KEY (active_task_id) REFERENCES platform_tasks(id);

    CREATE INDEX idx_platform_conversation_active_task
        ON platform_conversations(active_task_id)
        WHERE active_task_id IS NOT NULL;

    COMMENT ON TABLE platform_task_plans IS
        'Project-owned versioned plan and approval lifecycle; no Runtime execution ownership';
    COMMENT ON COLUMN platform_conversations.active_task_id IS
        'Current canonical Task focus; legacy task_id remains compatibility-only until M18';
END
$task_plan_foundation$;
