DO $reviewed_source_merge$
BEGIN
    IF to_regclass('public.platform_workspaces') IS NULL
            OR to_regclass('public.platform_source_repositories') IS NULL THEN
        RETURN;
    END IF;

    CREATE TABLE platform_source_merge_jobs (
        id UUID PRIMARY KEY,
        tenant_id VARCHAR(64) NOT NULL,
        project_id UUID NOT NULL,
        task_id UUID NOT NULL,
        source_repository_id UUID NOT NULL,
        workspace_id UUID NOT NULL,
        agent_run_id VARCHAR(36) NOT NULL,
        review_id UUID NOT NULL,
        commit_proposal_artifact_id UUID NOT NULL,
        target_ref VARCHAR(255) NOT NULL,
        expected_base_commit VARCHAR(64) NOT NULL,
        patch_hash VARCHAR(71) NOT NULL,
        commit_message VARCHAR(500) NOT NULL,
        idempotency_hash VARCHAR(71) NOT NULL,
        input_hash VARCHAR(71) NOT NULL,
        state VARCHAR(24) NOT NULL,
        prepared_commit VARCHAR(64),
        actual_target_commit VARCHAR(64),
        governance_approval_id UUID,
        failure_code VARCHAR(80),
        revision BIGINT NOT NULL DEFAULT 0,
        created_by VARCHAR(36) NOT NULL,
        created_at TIMESTAMPTZ NOT NULL,
        updated_at TIMESTAMPTZ NOT NULL,
        applied_at TIMESTAMPTZ,
        rolled_back_at TIMESTAMPTZ,
        CONSTRAINT fk_source_merge_project
            FOREIGN KEY(project_id, tenant_id) REFERENCES platform_projects(id, tenant_id),
        CONSTRAINT fk_source_merge_task
            FOREIGN KEY(project_id, task_id) REFERENCES platform_tasks(project_id, id),
        CONSTRAINT fk_source_merge_source
            FOREIGN KEY(source_repository_id, project_id, tenant_id)
            REFERENCES platform_source_repositories(id, project_id, tenant_id),
        CONSTRAINT fk_source_merge_workspace
            FOREIGN KEY(workspace_id, project_id, tenant_id)
            REFERENCES platform_workspaces(id, project_id, tenant_id),
        CONSTRAINT ck_source_merge_state CHECK(state IN(
            'PREPARING','READY','APPLYING','APPLIED_LOCAL','ROLLING_BACK','ROLLED_BACK',
            'CONFLICT','UNKNOWN','FAILED')),
        CONSTRAINT ck_source_merge_target_ref CHECK(target_ref ~ '^refs/heads/[A-Za-z0-9._/-]+$'),
        CONSTRAINT ck_source_merge_base CHECK(expected_base_commit ~ '^[0-9a-f]{40,64}$'),
        CONSTRAINT ck_source_merge_prepared CHECK(
            prepared_commit IS NULL OR prepared_commit ~ '^[0-9a-f]{40,64}$'),
        CONSTRAINT ck_source_merge_actual CHECK(
            actual_target_commit IS NULL OR actual_target_commit ~ '^[0-9a-f]{40,64}$'),
        CONSTRAINT ck_source_merge_patch_hash CHECK(patch_hash ~ '^sha256:[0-9a-f]{64}$'),
        CONSTRAINT ck_source_merge_idempotency_hash CHECK(
            idempotency_hash ~ '^sha256:[0-9a-f]{64}$'),
        CONSTRAINT ck_source_merge_input_hash CHECK(input_hash ~ '^sha256:[0-9a-f]{64}$'),
        CONSTRAINT ck_source_merge_revision CHECK(revision >= 0),
        CONSTRAINT ck_source_merge_applied CHECK(
            (state = 'APPLIED_LOCAL' AND applied_at IS NOT NULL)
            OR state <> 'APPLIED_LOCAL'),
        CONSTRAINT ck_source_merge_rolled_back CHECK(
            (state = 'ROLLED_BACK' AND rolled_back_at IS NOT NULL)
            OR state <> 'ROLLED_BACK')
    );

    CREATE UNIQUE INDEX uk_source_merge_idempotency
        ON platform_source_merge_jobs(tenant_id, created_by, idempotency_hash);
    CREATE INDEX idx_source_merge_project_created
        ON platform_source_merge_jobs(project_id, created_at DESC, id DESC);
    CREATE INDEX idx_source_merge_state_updated
        ON platform_source_merge_jobs(state, updated_at, id);

    COMMENT ON TABLE platform_source_merge_jobs IS
        'Project-owned reviewed local source integration ledger; remote branches are never updated';
END
$reviewed_source_merge$;
