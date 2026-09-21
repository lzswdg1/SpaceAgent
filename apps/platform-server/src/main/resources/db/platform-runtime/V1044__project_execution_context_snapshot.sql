DO $project_execution_context_snapshot$
BEGIN
    IF to_regclass('public.platform_agent_runs') IS NULL
            OR to_regclass('public.platform_run_checkpoints') IS NULL
            OR to_regclass('public.platform_conversation_context_snapshots') IS NULL
            OR to_regclass('public.platform_projects') IS NULL
            OR to_regclass('public.platform_project_directories') IS NULL
            OR to_regclass('public.platform_workspaces') IS NULL
            OR to_regclass('public.platform_tasks') IS NULL
            OR to_regclass('public.platform_task_plans') IS NULL
            OR to_regclass('public.platform_plan_steps') IS NULL
            OR to_regclass('public.platform_agent_versions') IS NULL
            OR to_regclass('public.platform_project_blueprints') IS NULL THEN
        RETURN;
    END IF;

    ALTER TABLE platform_agent_runs
        ADD CONSTRAINT uk_platform_agent_run_recovery_scope
        UNIQUE (id, tenant_id, owner_id, conversation_id, project_uuid,
                project_directory_id, workspace_id, task_uuid, task_plan_id,
                plan_step_id, agent_version_id);

    ALTER TABLE platform_run_checkpoints
        ADD CONSTRAINT uk_platform_run_checkpoint_run_scope UNIQUE (id, agent_run_id);

    ALTER TABLE platform_project_blueprints
        ADD CONSTRAINT uk_platform_blueprint_recovery_scope
        UNIQUE (id, project_id, tenant_id, version_number);

    ALTER TABLE platform_conversation_context_snapshots
        ADD CONSTRAINT uk_platform_conversation_snapshot_recovery_scope
        UNIQUE (id, conversation_id, version);

    CREATE TABLE platform_project_execution_context_snapshots (
        id                               UUID PRIMARY KEY,
        tenant_id                        VARCHAR(36) NOT NULL,
        owner_id                         VARCHAR(36) NOT NULL,
        project_id                       UUID NOT NULL,
        project_directory_id             UUID NOT NULL,
        conversation_id                  VARCHAR(36) NOT NULL,
        task_id                          UUID NOT NULL,
        task_plan_id                     UUID NOT NULL,
        plan_step_id                     UUID NOT NULL,
        agent_run_id                     VARCHAR(36) NOT NULL,
        agent_version_id                 UUID NOT NULL,
        workspace_id                     UUID NOT NULL,
        blueprint_id                     UUID,
        blueprint_version                INTEGER,
        conversation_context_snapshot_id VARCHAR(36),
        conversation_context_snapshot_version INTEGER,
        checkpoint_id                    VARCHAR(36),
        idempotency_hash                 VARCHAR(71) NOT NULL,
        input_hash                       VARCHAR(71) NOT NULL,
        snapshot_hash                    VARCHAR(71) NOT NULL,
        payload_json                     TEXT NOT NULL,
        created_at                       TIMESTAMPTZ NOT NULL,
        CONSTRAINT uk_platform_project_context_idempotency
            UNIQUE (tenant_id, owner_id, idempotency_hash),
        CONSTRAINT ck_platform_project_context_hashes
            CHECK (idempotency_hash ~ '^sha256:[0-9a-f]{64}$'
               AND input_hash ~ '^sha256:[0-9a-f]{64}$'
               AND snapshot_hash ~ '^sha256:[0-9a-f]{64}$'
               AND snapshot_hash = 'sha256:'
                   || encode(sha256(convert_to(payload_json, 'UTF8')), 'hex')),
        CONSTRAINT ck_platform_project_context_payload
            CHECK (jsonb_typeof(payload_json::JSONB) = 'object'
               AND octet_length(payload_json) <= 4000000),
        CONSTRAINT ck_platform_project_context_optional_versions
            CHECK ((blueprint_id IS NULL) = (blueprint_version IS NULL)
               AND (conversation_context_snapshot_id IS NULL)
                   = (conversation_context_snapshot_version IS NULL)),
        CONSTRAINT fk_platform_project_context_run_scope
            FOREIGN KEY (agent_run_id, tenant_id, owner_id, conversation_id, project_id,
                         project_directory_id, workspace_id, task_id, task_plan_id,
                         plan_step_id, agent_version_id)
            REFERENCES platform_agent_runs(
                id, tenant_id, owner_id, conversation_id, project_uuid,
                project_directory_id, workspace_id, task_uuid, task_plan_id,
                plan_step_id, agent_version_id) ON DELETE CASCADE,
        CONSTRAINT fk_platform_project_context_blueprint
            FOREIGN KEY (blueprint_id, project_id, tenant_id, blueprint_version)
            REFERENCES platform_project_blueprints(
                id, project_id, tenant_id, version_number),
        CONSTRAINT fk_platform_project_context_conversation_snapshot
            FOREIGN KEY (conversation_context_snapshot_id, conversation_id,
                         conversation_context_snapshot_version)
            REFERENCES platform_conversation_context_snapshots(id, conversation_id, version),
        CONSTRAINT fk_platform_project_context_checkpoint
            FOREIGN KEY (checkpoint_id, agent_run_id)
            REFERENCES platform_run_checkpoints(id, agent_run_id)
    );

    CREATE INDEX idx_platform_project_context_run_latest
        ON platform_project_execution_context_snapshots(agent_run_id, created_at DESC, id DESC);
    CREATE INDEX idx_platform_project_context_directory_latest
        ON platform_project_execution_context_snapshots(
            project_directory_id, created_at DESC, id DESC);

    COMMENT ON TABLE platform_project_execution_context_snapshots IS
        'Immutable Runtime recovery evidence; owner-module lifecycle state must be revalidated';
    COMMENT ON COLUMN platform_project_execution_context_snapshots.payload_json IS
        'Content-addressed bounded recovery package captured from owner APIs and exact OCI Workspace';
END
$project_execution_context_snapshot$;
