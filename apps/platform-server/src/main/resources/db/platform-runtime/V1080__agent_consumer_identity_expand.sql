-- M74 PR2 expand phase: add Agent identity and Runtime snapshot references beside legacy version IDs.
-- Contract removal is deferred until every producer/consumer writes these columns.

ALTER TABLE platform_project_intake_jobs
    ALTER COLUMN agent_version_id DROP NOT NULL;

ALTER TABLE platform_project_plan_step_assignments
    ALTER COLUMN agent_version_id DROP NOT NULL,
    ALTER COLUMN reviewer_agent_version_id DROP NOT NULL;
ALTER TABLE platform_project_coding_jobs
    ALTER COLUMN agent_version_id DROP NOT NULL,
    ALTER COLUMN reviewer_agent_version_id DROP NOT NULL;
ALTER TABLE platform_project_plan_executions
    ALTER COLUMN agent_version_id DROP NOT NULL,
    ALTER COLUMN reviewer_agent_version_id DROP NOT NULL;
ALTER TABLE platform_project_run_handoffs
    ALTER COLUMN target_agent_version_id DROP NOT NULL,
    ALTER COLUMN reviewer_agent_version_id DROP NOT NULL;
ALTER TABLE platform_agent_delegations
    ALTER COLUMN target_agent_version_id DROP NOT NULL;
ALTER TABLE platform_agent_reviews
    ALTER COLUMN reviewer_agent_version_id DROP NOT NULL;
ALTER TABLE platform_automation_executions
    ALTER COLUMN agent_version_id DROP NOT NULL;

ALTER TABLE platform_project_blueprints
    ADD COLUMN generated_by_agent_id VARCHAR(36),
    ADD COLUMN generated_by_run_configuration_snapshot_id VARCHAR(36),
    ADD CONSTRAINT fk_platform_blueprint_generated_agent
        FOREIGN KEY (generated_by_agent_id) REFERENCES platform_agent_definitions(id),
    ADD CONSTRAINT fk_platform_blueprint_generated_run_configuration
        FOREIGN KEY (generated_by_run_configuration_snapshot_id)
        REFERENCES platform_agent_run_configuration_snapshots(id) ON DELETE SET NULL;

UPDATE platform_project_blueprints blueprint
   SET generated_by_agent_id = COALESCE(
           (SELECT version.agent_id FROM platform_agent_versions version
             WHERE version.id = blueprint.generated_by_agent_version_id),
           (SELECT intake.agent_id FROM platform_project_intake_jobs intake
             WHERE intake.blueprint_id = blueprint.id LIMIT 1)),
       generated_by_run_configuration_snapshot_id =
           (SELECT intake.agent_run_id FROM platform_project_intake_jobs intake
             WHERE intake.blueprint_id = blueprint.id LIMIT 1)
 WHERE blueprint.generated_by_agent_version_id IS NOT NULL
    OR EXISTS (SELECT 1 FROM platform_project_intake_jobs intake
                WHERE intake.blueprint_id = blueprint.id);

ALTER TABLE platform_task_plans
    ADD COLUMN generated_by_agent_id VARCHAR(36),
    ADD COLUMN generated_by_run_configuration_snapshot_id VARCHAR(36),
    ADD CONSTRAINT fk_platform_task_plan_generated_agent
        FOREIGN KEY (generated_by_agent_id) REFERENCES platform_agent_definitions(id),
    ADD CONSTRAINT fk_platform_task_plan_generated_run_configuration
        FOREIGN KEY (generated_by_run_configuration_snapshot_id)
        REFERENCES platform_agent_run_configuration_snapshots(id) ON DELETE SET NULL;

UPDATE platform_task_plans plan
   SET generated_by_agent_id = COALESCE(
           (SELECT version.agent_id FROM platform_agent_versions version
             WHERE version.id = plan.generated_by_agent_version_id),
           (SELECT intake.agent_id FROM platform_project_intake_jobs intake
             WHERE intake.task_plan_id = plan.id LIMIT 1),
           (SELECT run.agent_id FROM platform_agent_runs run
             WHERE run.id = plan.source_agent_run_id)),
       generated_by_run_configuration_snapshot_id = COALESCE(
           plan.source_agent_run_id,
           (SELECT intake.agent_run_id FROM platform_project_intake_jobs intake
             WHERE intake.task_plan_id = plan.id LIMIT 1))
 WHERE plan.generated_by_agent_version_id IS NOT NULL
    OR plan.source_agent_run_id IS NOT NULL
    OR EXISTS (SELECT 1 FROM platform_project_intake_jobs intake
                WHERE intake.task_plan_id = plan.id);

ALTER TABLE platform_project_coding_jobs
    ADD COLUMN reviewer_agent_id VARCHAR(36);
UPDATE platform_project_coding_jobs job
   SET reviewer_agent_id = version.agent_id
  FROM platform_agent_versions version
 WHERE version.id = job.reviewer_agent_version_id;
ALTER TABLE platform_project_coding_jobs
    ADD CONSTRAINT fk_platform_project_coding_job_reviewer_agent
        FOREIGN KEY (reviewer_agent_id) REFERENCES platform_agent_definitions(id);

ALTER TABLE platform_project_plan_executions
    ADD COLUMN reviewer_agent_id VARCHAR(36);
UPDATE platform_project_plan_executions execution
   SET reviewer_agent_id = version.agent_id
  FROM platform_agent_versions version
 WHERE version.id = execution.reviewer_agent_version_id;
ALTER TABLE platform_project_plan_executions
    ADD CONSTRAINT fk_project_plan_execution_reviewer_agent
        FOREIGN KEY (reviewer_agent_id) REFERENCES platform_agent_definitions(id);

ALTER TABLE platform_project_run_handoffs
    ADD COLUMN reviewer_agent_id VARCHAR(36);
UPDATE platform_project_run_handoffs handoff
   SET reviewer_agent_id = version.agent_id
  FROM platform_agent_versions version
 WHERE version.id = handoff.reviewer_agent_version_id;
ALTER TABLE platform_project_run_handoffs
    ADD CONSTRAINT fk_project_run_handoff_reviewer_agent
        FOREIGN KEY (reviewer_agent_id) REFERENCES platform_agent_definitions(id);

ALTER TABLE platform_agent_reviews
    ADD COLUMN reviewer_agent_id VARCHAR(36);
UPDATE platform_agent_reviews review
   SET reviewer_agent_id = version.agent_id
  FROM platform_agent_versions version
 WHERE version.id = review.reviewer_agent_version_id;
ALTER TABLE platform_agent_reviews
    ADD CONSTRAINT fk_agent_review_reviewer_agent
        FOREIGN KEY (reviewer_agent_id) REFERENCES platform_agent_definitions(id);

CREATE INDEX idx_project_blueprint_generated_agent
    ON platform_project_blueprints(generated_by_agent_id, created_at DESC)
    WHERE generated_by_agent_id IS NOT NULL;
CREATE INDEX idx_task_plan_generated_agent
    ON platform_task_plans(generated_by_agent_id, created_at DESC)
    WHERE generated_by_agent_id IS NOT NULL;

COMMENT ON COLUMN platform_project_intake_jobs.agent_version_id IS
    'M74 legacy compatibility only; new Intake resolves agent_id and captures configuration on agent_run_id';
COMMENT ON COLUMN platform_project_blueprints.generated_by_run_configuration_snapshot_id IS
    'Runtime configuration evidence for Agent-generated Blueprint; not an Agent release/version';
COMMENT ON COLUMN platform_task_plans.generated_by_run_configuration_snapshot_id IS
    'Runtime configuration evidence for Agent-generated TaskPlan; not an Agent release/version';
