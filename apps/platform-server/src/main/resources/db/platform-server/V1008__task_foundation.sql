-- Task is Project-owned durable intent. Existing Runtime/Conversation task references
-- remain VARCHAR(36) compatibility fields until a later Task-scoped Runtime migration.
DO $task_foundation$
BEGIN
    IF to_regclass('public.platform_projects') IS NULL THEN
        RETURN;
    END IF;

    CREATE TABLE platform_tasks (
        id                        UUID PRIMARY KEY,
        project_id                UUID NOT NULL,
        parent_task_id            UUID,
        title                     VARCHAR(200) NOT NULL,
        goal                      TEXT NOT NULL,
        description               TEXT,
        constraints_json          JSONB NOT NULL DEFAULT '[]'::JSONB,
        acceptance_criteria_json  JSONB NOT NULL DEFAULT '[]'::JSONB,
        state                     VARCHAR(32) NOT NULL,
        created_at                TIMESTAMPTZ NOT NULL,
        updated_at                TIMESTAMPTZ NOT NULL,
        CONSTRAINT uk_platform_tasks_project_id_id UNIQUE (project_id, id),
        CONSTRAINT ck_platform_tasks_state CHECK (
            state IN ('PENDING', 'READY', 'IN_PROGRESS', 'BLOCKED',
                      'COMPLETED', 'FAILED', 'CANCELLED')
        ),
        CONSTRAINT ck_platform_tasks_constraints_array
            CHECK (jsonb_typeof(constraints_json) = 'array'),
        CONSTRAINT ck_platform_tasks_acceptance_array
            CHECK (jsonb_typeof(acceptance_criteria_json) = 'array'),
        CONSTRAINT fk_platform_tasks_project
            FOREIGN KEY (project_id) REFERENCES platform_projects(id),
        CONSTRAINT fk_platform_tasks_parent_same_project
            FOREIGN KEY (project_id, parent_task_id)
            REFERENCES platform_tasks(project_id, id)
    );

    CREATE INDEX idx_platform_tasks_project_created
        ON platform_tasks(project_id, created_at, id);

    CREATE INDEX idx_platform_tasks_parent
        ON platform_tasks(parent_task_id)
        WHERE parent_task_id IS NOT NULL;

    COMMENT ON TABLE platform_tasks IS
        'Project-owned durable Task intent and lifecycle; no Runtime or Workspace ownership';
END
$task_foundation$;
