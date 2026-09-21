DO $multi_agent$
BEGIN
    IF to_regclass('public.platform_artifacts') IS NULL THEN
        RETURN;
    END IF;

    ALTER TABLE platform_workspaces
        ADD COLUMN isolation_key VARCHAR(128) NOT NULL DEFAULT 'primary';

    DROP INDEX IF EXISTS uk_platform_workspace_active_task_source;
    CREATE UNIQUE INDEX uk_platform_workspace_active_isolation
        ON platform_workspaces(task_id, source_repository_id, isolation_key)
        WHERE writable AND state NOT IN ('ARCHIVED', 'CLEANED_UP', 'FAILED');

    CREATE TABLE platform_agent_delegations (
        id UUID PRIMARY KEY,
        tenant_id VARCHAR(36) NOT NULL,
        parent_run_id VARCHAR(36) NOT NULL,
        child_run_id VARCHAR(36) NOT NULL UNIQUE,
        task_plan_id UUID NOT NULL,
        plan_step_id UUID NOT NULL,
        child_task_id UUID NOT NULL,
        target_agent_id VARCHAR(36) NOT NULL,
        target_agent_version_id UUID NOT NULL,
        workspace_id UUID NOT NULL UNIQUE,
        handoff_id VARCHAR(36) NOT NULL UNIQUE,
        state VARCHAR(24) NOT NULL,
        created_at TIMESTAMPTZ NOT NULL,
        updated_at TIMESTAMPTZ NOT NULL,
        CONSTRAINT ck_agent_delegation_state
            CHECK (state IN ('ACTIVE', 'COMPLETED', 'FAILED', 'CANCELLED')),
        CONSTRAINT fk_agent_delegation_parent
            FOREIGN KEY (parent_run_id) REFERENCES platform_agent_runs(id),
        CONSTRAINT fk_agent_delegation_child
            FOREIGN KEY (child_run_id) REFERENCES platform_agent_runs(id),
        CONSTRAINT fk_agent_delegation_workspace
            FOREIGN KEY (workspace_id) REFERENCES platform_workspaces(id),
        CONSTRAINT fk_agent_delegation_handoff
            FOREIGN KEY (handoff_id) REFERENCES platform_run_handoffs(id),
        CONSTRAINT fk_agent_delegation_version
            FOREIGN KEY (target_agent_version_id) REFERENCES platform_agent_versions(id)
    );

    CREATE TABLE platform_agent_reviews (
        id UUID PRIMARY KEY,
        tenant_id VARCHAR(36) NOT NULL,
        parent_run_id VARCHAR(36) NOT NULL,
        child_run_id VARCHAR(36) NOT NULL,
        reviewer_agent_version_id UUID NOT NULL,
        artifact_ids_json JSONB NOT NULL,
        decision VARCHAR(32) NOT NULL,
        evidence TEXT,
        created_at TIMESTAMPTZ NOT NULL,
        decided_at TIMESTAMPTZ,
        CONSTRAINT ck_agent_review_decision
            CHECK (decision IN ('PENDING', 'APPROVED', 'CHANGES_REQUESTED')),
        CONSTRAINT fk_agent_review_parent
            FOREIGN KEY (parent_run_id) REFERENCES platform_agent_runs(id),
        CONSTRAINT fk_agent_review_child
            FOREIGN KEY (child_run_id) REFERENCES platform_agent_runs(id),
        CONSTRAINT fk_agent_review_version
            FOREIGN KEY (reviewer_agent_version_id) REFERENCES platform_agent_versions(id)
    );

    CREATE INDEX idx_agent_delegation_parent
        ON platform_agent_delegations(parent_run_id, created_at);
    CREATE INDEX idx_agent_review_parent
        ON platform_agent_reviews(parent_run_id, created_at);
END
$multi_agent$;
