DO $project_reconciliation_steps$
BEGIN
    IF to_regclass('public.platform_projects') IS NULL
            OR to_regclass('public.platform_project_directories') IS NULL
            OR to_regclass('public.platform_task_plans') IS NULL
            OR to_regclass('public.platform_plan_steps') IS NULL
            OR to_regclass('public.platform_project_plan_executions') IS NULL
            OR to_regclass('public.platform_project_plan_merge_barriers') IS NULL
            OR to_regclass('public.platform_source_merge_jobs') IS NULL
            OR to_regclass('public.platform_workspaces') IS NULL THEN
        RETURN;
    END IF;

    CREATE TABLE platform_project_reconciliation_steps (
        id                                  UUID PRIMARY KEY,
        tenant_id                           VARCHAR(64) NOT NULL,
        owner_id                            VARCHAR(36) NOT NULL,
        project_id                          UUID NOT NULL,
        project_directory_id                UUID NOT NULL,
        task_plan_id                        UUID NOT NULL,
        plan_step_id                        UUID NOT NULL,
        execution_id                        UUID NOT NULL,
        barrier_id                          UUID NOT NULL,
        source_merge_id                     UUID NOT NULL,
        expected_base_commit                VARCHAR(64) NOT NULL,
        actual_base_commit                  VARCHAR(64) NOT NULL,
        original_patch_artifact_id          UUID NOT NULL,
        original_commit_proposal_artifact_id UUID NOT NULL,
        original_test_report_artifact_id    UUID NOT NULL,
        original_review_id                  UUID NOT NULL,
        state                               VARCHAR(32) NOT NULL,
        proposal_workspace_id               UUID,
        proposal_id                         UUID,
        proposal_base_commit                VARCHAR(64),
        resolution_patch_artifact_id        UUID,
        resolution_commit_proposal_artifact_id UUID,
        resolution_test_report_artifact_id  UUID,
        resolution_review_id                UUID,
        resolution_source_merge_id          UUID,
        resolution_expected_base_commit     VARCHAR(64),
        resolution_actual_base_commit       VARCHAR(64),
        blocked_code                        VARCHAR(120),
        revision                            BIGINT NOT NULL,
        created_at                          TIMESTAMPTZ NOT NULL,
        updated_at                          TIMESTAMPTZ NOT NULL,
        resolved_at                         TIMESTAMPTZ,
        CONSTRAINT uk_project_reconciliation_source_merge UNIQUE (source_merge_id),
        CONSTRAINT fk_project_reconciliation_project
            FOREIGN KEY (project_id, tenant_id) REFERENCES platform_projects(id, tenant_id),
        CONSTRAINT fk_project_reconciliation_directory
            FOREIGN KEY (project_directory_id, project_id, tenant_id)
            REFERENCES platform_project_directories(id, project_id, tenant_id),
        CONSTRAINT fk_project_reconciliation_plan
            FOREIGN KEY (task_plan_id, project_id)
            REFERENCES platform_task_plans(id, project_id),
        CONSTRAINT fk_project_reconciliation_step
            FOREIGN KEY (task_plan_id, plan_step_id)
            REFERENCES platform_plan_steps(task_plan_id, id),
        CONSTRAINT fk_project_reconciliation_execution
            FOREIGN KEY (execution_id) REFERENCES platform_project_plan_executions(id),
        CONSTRAINT fk_project_reconciliation_barrier
            FOREIGN KEY (barrier_id) REFERENCES platform_project_plan_merge_barriers(execution_id),
        CONSTRAINT fk_project_reconciliation_source_merge
            FOREIGN KEY (source_merge_id) REFERENCES platform_source_merge_jobs(id),
        CONSTRAINT fk_project_reconciliation_resolution_merge
            FOREIGN KEY (resolution_source_merge_id) REFERENCES platform_source_merge_jobs(id),
        CONSTRAINT fk_project_reconciliation_workspace
            FOREIGN KEY (proposal_workspace_id, project_directory_id, project_id, tenant_id)
            REFERENCES platform_workspaces(id, project_directory_id, project_id, tenant_id),
        CONSTRAINT ck_project_reconciliation_scope
            CHECK (barrier_id = execution_id AND revision > 0),
        CONSTRAINT ck_project_reconciliation_base_commits
            CHECK (expected_base_commit ~ '^[0-9a-f]{40,64}$'
                AND actual_base_commit ~ '^[0-9a-f]{40,64}$'
                AND (proposal_base_commit IS NULL OR proposal_base_commit ~ '^[0-9a-f]{40,64}$')
                AND (resolution_expected_base_commit IS NULL
                    OR resolution_expected_base_commit ~ '^[0-9a-f]{40,64}$')
                AND (resolution_actual_base_commit IS NULL
                    OR resolution_actual_base_commit ~ '^[0-9a-f]{40,64}$')),
        CONSTRAINT ck_project_reconciliation_state
            CHECK (
                (state = 'WAITING_RECONCILIATION'
                    AND proposal_workspace_id IS NULL AND proposal_id IS NULL
                    AND proposal_base_commit IS NULL AND resolution_patch_artifact_id IS NULL
                    AND resolution_commit_proposal_artifact_id IS NULL
                    AND resolution_test_report_artifact_id IS NULL AND resolution_review_id IS NULL
                    AND resolution_source_merge_id IS NULL AND resolution_expected_base_commit IS NULL
                    AND resolution_actual_base_commit IS NULL AND blocked_code IS NULL
                    AND resolved_at IS NULL)
                OR (state = 'PROPOSAL_READY'
                    AND proposal_workspace_id IS NOT NULL AND proposal_id IS NOT NULL
                    AND proposal_base_commit IS NOT NULL AND resolution_patch_artifact_id IS NULL
                    AND resolution_commit_proposal_artifact_id IS NULL
                    AND resolution_test_report_artifact_id IS NULL AND resolution_review_id IS NULL
                    AND resolution_source_merge_id IS NULL AND resolution_expected_base_commit IS NULL
                    AND resolution_actual_base_commit IS NULL AND blocked_code IS NULL
                    AND resolved_at IS NULL)
                OR (state = 'BLOCKED' AND blocked_code IS NOT NULL
                    AND resolution_patch_artifact_id IS NULL
                    AND resolution_commit_proposal_artifact_id IS NULL
                    AND resolution_test_report_artifact_id IS NULL AND resolution_review_id IS NULL
                    AND resolution_source_merge_id IS NULL AND resolution_expected_base_commit IS NULL
                    AND resolution_actual_base_commit IS NULL AND resolved_at IS NULL)
                OR (state = 'RESOLVED'
                    AND proposal_workspace_id IS NOT NULL AND proposal_id IS NOT NULL
                    AND proposal_base_commit IS NOT NULL AND resolution_patch_artifact_id IS NOT NULL
                    AND resolution_commit_proposal_artifact_id IS NOT NULL
                    AND resolution_test_report_artifact_id IS NOT NULL AND resolution_review_id IS NOT NULL
                    AND resolution_source_merge_id IS NOT NULL
                    AND resolution_expected_base_commit IS NOT NULL
                    AND resolution_actual_base_commit IS NOT NULL AND blocked_code IS NULL
                    AND resolved_at IS NOT NULL)
            )
    );

    CREATE INDEX idx_project_reconciliation_project_state
        ON platform_project_reconciliation_steps(project_id, owner_id, state, updated_at DESC, id DESC);
    CREATE INDEX idx_project_reconciliation_execution
        ON platform_project_reconciliation_steps(execution_id, state, updated_at DESC);

    COMMENT ON TABLE platform_project_reconciliation_steps IS
        'Project-owned immutable base-drift audit chain; Runtime may only project its state';
END
$project_reconciliation_steps$;
