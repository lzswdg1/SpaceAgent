-- M74 PR3 contract phase: Agent has one mutable current configuration.
-- Historical execution reproducibility remains in Runtime-owned immutable Run snapshots.

DO $rewrite_agent_version_trace_views$
DECLARE
    definition TEXT;
BEGIN
    IF to_regclass('platform_trace_roots') IS NOT NULL THEN
        definition := pg_get_viewdef('platform_trace_roots'::regclass, true);
        IF position('run.agent_version_id' IN definition) > 0 THEN
            definition := replace(
                definition,
                'run.agent_version_id',
                '(SELECT snapshot.id FROM platform_agent_run_configuration_snapshots snapshot WHERE snapshot.run_id = run.id)');
            EXECUTE 'CREATE OR REPLACE VIEW platform_trace_roots AS ' || definition;
        END IF;
    END IF;

    IF to_regclass('platform_trace_spans') IS NOT NULL THEN
        definition := pg_get_viewdef('platform_trace_spans'::regclass, true);
        definition := replace(definition, 'review.reviewer_agent_version_id',
                              'review.reviewer_agent_id');
        definition := replace(definition, '''reviewerAgentVersionId''', '''reviewerAgentId''');
        EXECUTE 'CREATE OR REPLACE VIEW platform_trace_spans AS ' || definition;
    END IF;
END
$rewrite_agent_version_trace_views$;

ALTER TABLE platform_project_execution_context_snapshots
    ADD COLUMN run_configuration_snapshot_id VARCHAR(36);

UPDATE platform_project_execution_context_snapshots context_snapshot
   SET run_configuration_snapshot_id = run_snapshot.id
  FROM platform_agent_run_configuration_snapshots run_snapshot
 WHERE run_snapshot.run_id = context_snapshot.agent_run_id;

ALTER TABLE platform_project_execution_context_snapshots
    ALTER COLUMN run_configuration_snapshot_id SET NOT NULL,
    DROP CONSTRAINT fk_platform_project_context_run_scope;

ALTER TABLE platform_agent_runs
    DROP CONSTRAINT uk_platform_agent_run_recovery_scope;

ALTER TABLE platform_agent_runs
    ADD CONSTRAINT uk_platform_agent_run_recovery_scope
        UNIQUE (id, tenant_id, owner_id, conversation_id, project_uuid,
                project_directory_id, workspace_id, task_uuid, task_plan_id, plan_step_id);

ALTER TABLE platform_project_execution_context_snapshots
    ADD CONSTRAINT fk_platform_project_context_run_scope
        FOREIGN KEY (agent_run_id, tenant_id, owner_id, conversation_id, project_id,
                     project_directory_id, workspace_id, task_id, task_plan_id, plan_step_id)
        REFERENCES platform_agent_runs(
            id, tenant_id, owner_id, conversation_id, project_uuid,
            project_directory_id, workspace_id, task_uuid, task_plan_id, plan_step_id)
        ON DELETE CASCADE,
    ADD CONSTRAINT fk_platform_project_context_run_configuration
        FOREIGN KEY (run_configuration_snapshot_id)
        REFERENCES platform_agent_run_configuration_snapshots(id) ON DELETE CASCADE,
    DROP COLUMN agent_version_id;

ALTER TABLE platform_agent_runs
    DROP CONSTRAINT fk_platform_agent_run_version,
    DROP COLUMN agent_version_id;
DROP INDEX IF EXISTS idx_platform_agent_runs_version;

ALTER TABLE platform_agent_definitions
    DROP CONSTRAINT fk_platform_agent_definition_current_version,
    DROP COLUMN current_agent_version_id;

ALTER TABLE platform_project_blueprints
    DROP CONSTRAINT fk_platform_blueprint_agent_version,
    DROP COLUMN generated_by_agent_version_id;

ALTER TABLE platform_task_plans
    DROP CONSTRAINT fk_platform_task_plan_generator,
    DROP COLUMN generated_by_agent_version_id;

ALTER TABLE platform_project_intake_jobs
    DROP CONSTRAINT fk_platform_project_intake_agent_version,
    DROP COLUMN agent_version_id;

ALTER TABLE platform_project_coding_jobs
    DROP CONSTRAINT fk_project_coding_version,
    DROP CONSTRAINT fk_project_coding_reviewer_version,
    DROP COLUMN agent_version_id,
    DROP COLUMN reviewer_agent_version_id;

ALTER TABLE platform_project_run_handoffs
    DROP CONSTRAINT fk_project_run_handoff_agent_version,
    DROP CONSTRAINT fk_project_run_handoff_reviewer_version,
    DROP COLUMN target_agent_version_id,
    DROP COLUMN reviewer_agent_version_id;

ALTER TABLE platform_project_plan_executions
    DROP CONSTRAINT fk_platform_plan_execution_agent_version,
    DROP CONSTRAINT fk_platform_plan_execution_reviewer_version,
    DROP COLUMN agent_version_id,
    DROP COLUMN reviewer_agent_version_id;

ALTER TABLE platform_project_plan_step_assignments
    DROP CONSTRAINT ck_project_plan_step_assignment_values,
    DROP CONSTRAINT fk_project_plan_step_assignment_agent_version,
    DROP CONSTRAINT fk_project_plan_step_assignment_reviewer_version,
    DROP COLUMN agent_version_id,
    DROP COLUMN reviewer_agent_version_id,
    ADD CONSTRAINT ck_project_plan_step_assignment_values
        CHECK (revision > 0 AND agent_id <> reviewer_agent_id
            AND capability_hash ~ '^[0-9a-f]{64}$'
            AND configuration_hash ~ '^[0-9a-f]{64}$'
            AND assignment_hash ~ '^[0-9a-f]{64}$');

ALTER TABLE platform_agent_delegations
    DROP CONSTRAINT fk_agent_delegation_version,
    DROP COLUMN target_agent_version_id;

ALTER TABLE platform_agent_reviews
    DROP CONSTRAINT fk_agent_review_version,
    DROP COLUMN reviewer_agent_version_id;

ALTER TABLE platform_automation_executions
    DROP CONSTRAINT fk_platform_automation_execution_version,
    DROP COLUMN agent_version_id;

DROP TABLE platform_agent_version_activation_schedules;
DROP TABLE platform_agent_version_review_comments;
DROP TABLE platform_agent_version_review_decisions;
DROP TABLE platform_agent_version_reviews;
DROP TABLE platform_agent_version_governance_policies;
DROP TABLE platform_agent_version_mcp_bindings;
DROP TABLE platform_agent_versions;

DO $rename_trace_snapshot_columns$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema=current_schema() AND table_name='platform_trace_roots'
                  AND column_name='agent_version_id') THEN
        ALTER VIEW platform_trace_roots
            RENAME COLUMN agent_version_id TO run_configuration_snapshot_id;
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema=current_schema() AND table_name='platform_trace_summaries'
                  AND column_name='agent_version_id') THEN
        ALTER VIEW platform_trace_summaries
            RENAME COLUMN agent_version_id TO run_configuration_snapshot_id;
    END IF;
END
$rename_trace_snapshot_columns$;

COMMENT ON COLUMN platform_project_execution_context_snapshots.run_configuration_snapshot_id IS
    'Immutable Runtime-owned Agent configuration evidence captured for this Run';
