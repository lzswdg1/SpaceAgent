DO $project_intake_jobs$
BEGIN
    IF to_regclass('public.platform_projects') IS NULL
            OR to_regclass('public.platform_project_directories') IS NULL
            OR to_regclass('public.platform_source_repositories') IS NULL
            OR to_regclass('public.platform_conversations') IS NULL
            OR to_regclass('public.platform_agent_versions') IS NULL
            OR to_regclass('public.platform_agent_runs') IS NULL THEN
        RETURN;
    END IF;

    CREATE TABLE platform_project_intake_jobs (
        id                   UUID PRIMARY KEY,
        tenant_id            VARCHAR(36) NOT NULL,
        owner_id             VARCHAR(36) NOT NULL,
        project_id           UUID NOT NULL,
        project_directory_id UUID NOT NULL,
        source_repository_id UUID NOT NULL,
        is_sandbox_root      BOOLEAN NOT NULL DEFAULT TRUE,
        conversation_id      VARCHAR(36),
        agent_id             VARCHAR(36) NOT NULL,
        agent_version_id     UUID NOT NULL,
        goal                 TEXT NOT NULL,
        idempotency_hash     VARCHAR(71) NOT NULL,
        input_hash           VARCHAR(71) NOT NULL,
        state                VARCHAR(24) NOT NULL,
        attempt              INTEGER NOT NULL DEFAULT 0,
        claim_owner          VARCHAR(160),
        claim_token          UUID,
        fencing_token        BIGINT NOT NULL DEFAULT 0,
        lease_until          TIMESTAMPTZ,
        workspace_ref        VARCHAR(80),
        source_head_commit   VARCHAR(64),
        inspection_hash      VARCHAR(71),
        inspection_json      TEXT,
        proposal_hash        VARCHAR(71),
        proposal_json        TEXT,
        agent_run_id         VARCHAR(36),
        blueprint_id         UUID,
        root_task_id         UUID,
        task_plan_id         UUID,
        safe_error_code      VARCHAR(120),
        reviewed_by          VARCHAR(36),
        review_reason        VARCHAR(500),
        reviewed_at          TIMESTAMPTZ,
        revision             BIGINT NOT NULL DEFAULT 1,
        created_at           TIMESTAMPTZ NOT NULL,
        started_at           TIMESTAMPTZ,
        updated_at           TIMESTAMPTZ NOT NULL,
        completed_at         TIMESTAMPTZ,
        workspace_cleaned_at TIMESTAMPTZ,
        CONSTRAINT uk_platform_project_intake_idempotency
            UNIQUE (tenant_id, owner_id, idempotency_hash),
        CONSTRAINT ck_platform_project_intake_state
            CHECK (state IN ('PENDING','RUNNING','PROPOSED','CONFIRMED','REJECTED','FAILED','BLOCKED')),
        CONSTRAINT ck_platform_project_intake_hashes
            CHECK (idempotency_hash ~ '^sha256:[0-9a-f]{64}$'
               AND input_hash ~ '^sha256:[0-9a-f]{64}$'
               AND (inspection_hash IS NULL OR inspection_hash ~ '^sha256:[0-9a-f]{64}$')
               AND (proposal_hash IS NULL OR proposal_hash ~ '^sha256:[0-9a-f]{64}$')),
        CONSTRAINT ck_platform_project_intake_payload_hashes
            CHECK ((inspection_hash IS NULL) = (inspection_json IS NULL)
               AND (proposal_hash IS NULL) = (proposal_json IS NULL)
               AND (inspection_json IS NULL OR
                    (jsonb_typeof(inspection_json::JSONB) = 'object'
                     AND octet_length(inspection_json) <= 500000
                     AND inspection_hash = 'sha256:' ||
                         encode(sha256(convert_to(inspection_json, 'UTF8')), 'hex')))
               AND (proposal_json IS NULL OR
                    (jsonb_typeof(proposal_json::JSONB) = 'object'
                     AND octet_length(proposal_json) <= 500000
                     AND proposal_hash = 'sha256:' ||
                         encode(sha256(convert_to(proposal_json, 'UTF8')), 'hex')))),
        CONSTRAINT ck_platform_project_intake_proposal_lifecycle
            CHECK (state NOT IN ('PROPOSED','CONFIRMED','REJECTED') OR
                   (inspection_json IS NOT NULL AND proposal_json IS NOT NULL)),
        CONSTRAINT ck_platform_project_intake_claim
            CHECK ((state = 'RUNNING') =
                   (claim_owner IS NOT NULL AND claim_token IS NOT NULL AND lease_until IS NOT NULL)),
        CONSTRAINT ck_platform_project_intake_review
            CHECK ((reviewed_by IS NULL) = (reviewed_at IS NULL)
               AND (reviewed_by IS NULL) = (review_reason IS NULL)),
        CONSTRAINT ck_platform_project_intake_workspace
            CHECK (workspace_ref IS NULL OR workspace_ref = 'workspaces/' || id::TEXT),
        CONSTRAINT ck_platform_project_intake_terminal
            CHECK ((state NOT IN ('PROPOSED','CONFIRMED','REJECTED','FAILED','BLOCKED'))
                    OR completed_at IS NOT NULL),
        CONSTRAINT ck_platform_project_intake_confirmed
            CHECK (state <> 'CONFIRMED' OR
                   (blueprint_id IS NOT NULL AND root_task_id IS NOT NULL
                    AND task_plan_id IS NOT NULL AND reviewed_by IS NOT NULL)),
        CONSTRAINT ck_platform_project_intake_rejected
            CHECK (state <> 'REJECTED' OR reviewed_by IS NOT NULL),
        CONSTRAINT ck_platform_project_intake_error
            CHECK (state NOT IN ('FAILED','BLOCKED') OR safe_error_code IS NOT NULL),
        CONSTRAINT ck_platform_project_intake_values
            CHECK (length(goal) BETWEEN 1 AND 8000 AND attempt >= 0
               AND fencing_token >= 0 AND revision > 0),
        CONSTRAINT fk_platform_project_intake_project
            FOREIGN KEY (project_id, tenant_id) REFERENCES platform_projects(id, tenant_id),
        CONSTRAINT fk_platform_project_intake_directory_source
            FOREIGN KEY (project_directory_id, project_id, tenant_id, source_repository_id,
                         is_sandbox_root)
            REFERENCES platform_project_directories(
                id, project_id, tenant_id, source_repository_id, is_sandbox_root),
        CONSTRAINT fk_platform_project_intake_source
            FOREIGN KEY (source_repository_id, project_id, tenant_id)
            REFERENCES platform_source_repositories(id, project_id, tenant_id),
        CONSTRAINT fk_platform_project_intake_owner
            FOREIGN KEY (owner_id) REFERENCES platform_users(id),
        CONSTRAINT fk_platform_project_intake_conversation
            FOREIGN KEY (conversation_id) REFERENCES platform_conversations(id)
            ON DELETE SET NULL,
        CONSTRAINT fk_platform_project_intake_agent
            FOREIGN KEY (agent_id) REFERENCES platform_agent_definitions(id),
        CONSTRAINT fk_platform_project_intake_agent_version
            FOREIGN KEY (agent_version_id) REFERENCES platform_agent_versions(id),
        CONSTRAINT fk_platform_project_intake_run
            FOREIGN KEY (agent_run_id) REFERENCES platform_agent_runs(id)
            ON DELETE SET NULL,
        CONSTRAINT fk_platform_project_intake_blueprint
            FOREIGN KEY (blueprint_id) REFERENCES platform_project_blueprints(id),
        CONSTRAINT fk_platform_project_intake_root_task
            FOREIGN KEY (root_task_id) REFERENCES platform_tasks(id),
        CONSTRAINT fk_platform_project_intake_task_plan
            FOREIGN KEY (task_plan_id) REFERENCES platform_task_plans(id),
        CONSTRAINT ck_platform_project_intake_sandbox_root CHECK (is_sandbox_root)
    );

    CREATE INDEX idx_platform_project_intake_directory
        ON platform_project_intake_jobs(project_directory_id, created_at DESC, id DESC);
    CREATE INDEX idx_platform_project_intake_pending
        ON platform_project_intake_jobs(created_at, id)
        WHERE state IN ('PENDING','RUNNING');
    CREATE UNIQUE INDEX uk_platform_project_intake_active_directory
        ON platform_project_intake_jobs(project_directory_id)
        WHERE state IN ('PENDING','RUNNING');
    CREATE INDEX idx_platform_project_intake_cleanup
        ON platform_project_intake_jobs(completed_at, id)
        WHERE workspace_ref IS NOT NULL AND workspace_cleaned_at IS NULL;

    COMMENT ON TABLE platform_project_intake_jobs IS
        'Project-owned pre-Task read-only analysis and confirmation evidence';
    COMMENT ON COLUMN platform_project_intake_jobs.inspection_json IS
        'Bounded redacted source evidence; never Provider/MCP credentials or unredacted secrets';
END
$project_intake_jobs$;
