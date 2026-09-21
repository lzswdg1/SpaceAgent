-- M18 is additive: canonical UUID references coexist with legacy VARCHAR fields until
-- a separately reviewed cleanup migration proves every historical row is converged.
DO $task_scoped_runtime$
BEGIN
    IF to_regclass('public.platform_agent_runs') IS NULL THEN
        RETURN;
    END IF;

    ALTER TABLE platform_agent_runs ADD COLUMN tenant_id VARCHAR(36);
    ALTER TABLE platform_agent_runs ADD COLUMN project_uuid UUID;
    ALTER TABLE platform_agent_runs ADD COLUMN task_uuid UUID;
    ALTER TABLE platform_agent_runs ADD COLUMN task_plan_id UUID;
    ALTER TABLE platform_agent_runs ADD COLUMN plan_step_id UUID;
    ALTER TABLE platform_agent_runs
        ADD COLUMN execution_cursor JSONB NOT NULL DEFAULT
            '{"phase":"planning","stepId":null,"checkpointId":null,"checkpointSequence":0}'::JSONB;
    ALTER TABLE platform_agent_runs ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;

    IF to_regclass('public.platform_projects') IS NOT NULL
            AND to_regclass('public.platform_tasks') IS NOT NULL
            AND to_regclass('public.platform_task_plans') IS NOT NULL
            AND to_regclass('public.platform_plan_steps') IS NOT NULL THEN
        UPDATE platform_agent_runs run
        SET project_uuid = project.id,
            task_uuid = task.id,
            tenant_id = COALESCE(run.tenant_id, project.tenant_id)
        FROM platform_projects project
        JOIN platform_tasks task ON task.project_id = project.id
        WHERE run.project_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
          AND run.task_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
          AND project.id = run.project_id::UUID
          AND task.id = run.task_id::UUID;

        ALTER TABLE platform_plan_steps
            DROP CONSTRAINT ck_platform_plan_step_state;
        ALTER TABLE platform_plan_steps
            ADD CONSTRAINT ck_platform_plan_step_state
            CHECK (state IN ('PENDING', 'READY', 'IN_PROGRESS', 'BLOCKED',
                             'COMPLETED', 'FAILED', 'CANCELLED'));
        ALTER TABLE platform_plan_steps
            ADD CONSTRAINT uk_platform_plan_step_plan_id_child
            UNIQUE (task_plan_id, id, child_task_id);

        ALTER TABLE platform_agent_runs
            ADD CONSTRAINT fk_platform_agent_run_project_uuid
            FOREIGN KEY (project_uuid) REFERENCES platform_projects(id);
        ALTER TABLE platform_agent_runs
            ADD CONSTRAINT fk_platform_agent_run_task_uuid
            FOREIGN KEY (project_uuid, task_uuid)
            REFERENCES platform_tasks(project_id, id);
        ALTER TABLE platform_agent_runs
            ADD CONSTRAINT fk_platform_agent_run_task_plan
            FOREIGN KEY (task_plan_id, project_uuid)
            REFERENCES platform_task_plans(id, project_id);
        ALTER TABLE platform_agent_runs
            ADD CONSTRAINT fk_platform_agent_run_plan_step_task
            FOREIGN KEY (task_plan_id, plan_step_id, task_uuid)
            REFERENCES platform_plan_steps(task_plan_id, id, child_task_id);
        ALTER TABLE platform_agent_runs
            ADD CONSTRAINT ck_platform_agent_run_canonical_task_scope
            CHECK (
                (project_uuid IS NULL AND task_uuid IS NULL
                    AND task_plan_id IS NULL AND plan_step_id IS NULL)
                OR
                (project_uuid IS NOT NULL AND task_uuid IS NOT NULL
                    AND task_plan_id IS NOT NULL AND plan_step_id IS NOT NULL
                    AND tenant_id IS NOT NULL)
            );

        CREATE INDEX idx_platform_agent_runs_task_uuid
            ON platform_agent_runs(task_uuid, created_at DESC)
            WHERE task_uuid IS NOT NULL;
        CREATE INDEX idx_platform_agent_runs_plan_step
            ON platform_agent_runs(task_plan_id, plan_step_id, created_at DESC)
            WHERE plan_step_id IS NOT NULL;

        IF to_regclass('public.platform_conversations') IS NOT NULL THEN
            ALTER TABLE platform_conversations ADD COLUMN project_uuid UUID;
            ALTER TABLE platform_conversations ADD COLUMN task_uuid UUID;

            UPDATE platform_conversations conversation
            SET project_uuid = project.id
            FROM platform_projects project
            WHERE conversation.project_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
              AND project.id = conversation.project_id::UUID;

            UPDATE platform_conversations conversation
            SET task_uuid = task.id
            FROM platform_tasks task
            WHERE conversation.project_uuid = task.project_id
              AND conversation.task_id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
              AND task.id = conversation.task_id::UUID;

            ALTER TABLE platform_conversations
                ADD CONSTRAINT fk_platform_conversation_project_uuid
                FOREIGN KEY (project_uuid) REFERENCES platform_projects(id);
            ALTER TABLE platform_conversations
                ADD CONSTRAINT fk_platform_conversation_task_uuid
                FOREIGN KEY (project_uuid, task_uuid)
                REFERENCES platform_tasks(project_id, id);
        END IF;
    END IF;

    CREATE TABLE platform_run_events (
        id                VARCHAR(80) PRIMARY KEY,
        agent_run_id      VARCHAR(36) NOT NULL,
        sequence_number   BIGINT NOT NULL,
        event_type        VARCHAR(64) NOT NULL,
        payload           JSONB NOT NULL DEFAULT '{}'::JSONB,
        execution_cursor  JSONB NOT NULL,
        created_at        TIMESTAMPTZ NOT NULL,
        CONSTRAINT uk_platform_run_event_sequence
            UNIQUE (agent_run_id, sequence_number),
        CONSTRAINT ck_platform_run_event_type CHECK (
            event_type IN ('RUN_CREATED', 'RUN_STATE_CHANGED', 'STEP_STARTED',
                           'STEP_COMPLETED', 'STEP_FAILED', 'CHECKPOINT_CREATED',
                           'CURSOR_ADVANCED', 'ORCHESTRATION_COMMAND_ACCEPTED')
        ),
        CONSTRAINT fk_platform_run_event_run
            FOREIGN KEY (agent_run_id)
            REFERENCES platform_agent_runs(id) ON DELETE CASCADE
    );

    CREATE INDEX idx_platform_run_events_run
        ON platform_run_events(agent_run_id, sequence_number);

    COMMENT ON TABLE platform_run_events IS
        'Java-owned append-only execution history; TypeScript may only propose commands';
END
$task_scoped_runtime$;
