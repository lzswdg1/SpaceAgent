DO $project_directory_sandbox_hierarchy$
BEGIN
    IF to_regclass('public.platform_projects') IS NULL
            OR to_regclass('public.platform_source_repositories') IS NULL
            OR to_regclass('public.platform_workspaces') IS NULL
            OR to_regclass('public.platform_conversations') IS NULL
            OR to_regclass('public.platform_agent_runs') IS NULL THEN
        RETURN;
    END IF;

    CREATE TABLE platform_project_directories (
        id                   UUID PRIMARY KEY,
        tenant_id            VARCHAR(36) NOT NULL,
        project_id           UUID NOT NULL,
        source_repository_id UUID,
        name                 VARCHAR(120) NOT NULL,
        relative_path        VARCHAR(500) NOT NULL,
        is_sandbox_root      BOOLEAN GENERATED ALWAYS AS (relative_path = '.') STORED,
        is_default           BOOLEAN NOT NULL,
        state                VARCHAR(24) NOT NULL,
        created_by           VARCHAR(36) NOT NULL,
        created_at           TIMESTAMPTZ NOT NULL,
        updated_at           TIMESTAMPTZ NOT NULL,
        CONSTRAINT uk_platform_project_directory_scope
            UNIQUE (id, project_id, tenant_id),
        CONSTRAINT uk_platform_project_directory_source_scope
            UNIQUE (id, project_id, tenant_id, source_repository_id, is_sandbox_root),
        CONSTRAINT ck_platform_project_directory_state
            CHECK (state IN ('ACTIVE', 'ARCHIVED')),
        CONSTRAINT ck_platform_project_directory_authority
            CHECK ((is_default AND source_repository_id IS NULL)
                OR (NOT is_default AND source_repository_id IS NOT NULL)),
        CONSTRAINT ck_platform_project_directory_relative_path
            CHECK (length(relative_path) BETWEEN 1 AND 500
                AND relative_path !~ E'[/\\\\]$'
                AND relative_path !~ E'^[/\\\\]'
                AND relative_path !~ '^[A-Za-z]:/'
                AND relative_path !~ E'(^|/)\\.\\.?(/|$)'
                AND relative_path NOT LIKE '%//%'
                OR relative_path = '.'),
        CONSTRAINT fk_platform_project_directory_project
            FOREIGN KEY (project_id, tenant_id)
            REFERENCES platform_projects(id, tenant_id),
        CONSTRAINT fk_platform_project_directory_source
            FOREIGN KEY (source_repository_id, project_id, tenant_id)
            REFERENCES platform_source_repositories(id, project_id, tenant_id),
        CONSTRAINT fk_platform_project_directory_creator
            FOREIGN KEY (created_by) REFERENCES platform_users(id)
    );

    CREATE UNIQUE INDEX uk_platform_project_directory_default
        ON platform_project_directories(project_id) WHERE is_default;
    CREATE UNIQUE INDEX uk_platform_project_directory_active_source_path
        ON platform_project_directories(project_id, source_repository_id, relative_path)
        WHERE state = 'ACTIVE' AND source_repository_id IS NOT NULL;
    CREATE INDEX idx_platform_project_directory_project
        ON platform_project_directories(project_id, is_default DESC, name, id);

    INSERT INTO platform_project_directories(
        id, tenant_id, project_id, source_repository_id, name, relative_path,
        is_default, state, created_by, created_at, updated_at)
    SELECT gen_random_uuid(), project.tenant_id, project.id, NULL,
           left(project.name, 120), '.', TRUE,
           CASE project.status WHEN 'ARCHIVED' THEN 'ARCHIVED' ELSE 'ACTIVE' END,
           project.owner_id, project.created_at, project.updated_at
      FROM platform_projects project;

    INSERT INTO platform_project_directories(
        id, tenant_id, project_id, source_repository_id, name, relative_path,
        is_default, state, created_by, created_at, updated_at)
    SELECT gen_random_uuid(), source.tenant_id, source.project_id, source.id,
           left(source.display_name, 120), '.', FALSE,
           CASE source.state WHEN 'ARCHIVED' THEN 'ARCHIVED' ELSE 'ACTIVE' END,
           source.created_by, source.created_at, source.updated_at
      FROM platform_source_repositories source;

    ALTER TABLE platform_conversations ADD COLUMN project_directory_id UUID;
    UPDATE platform_conversations conversation
       SET project_directory_id = directory.id
      FROM platform_project_directories directory
     WHERE directory.project_id = conversation.project_uuid
       AND directory.is_default;
    ALTER TABLE platform_conversations
        ADD CONSTRAINT fk_platform_conversation_project_directory
        FOREIGN KEY (project_directory_id, project_uuid, tenant_id)
        REFERENCES platform_project_directories(id, project_id, tenant_id),
        ADD CONSTRAINT ck_platform_conversation_project_directory
        CHECK (project_directory_id IS NULL OR project_uuid IS NOT NULL);
    CREATE INDEX idx_platform_conversation_project_directory
        ON platform_conversations(project_directory_id, updated_at DESC)
        WHERE project_directory_id IS NOT NULL;

    ALTER TABLE platform_workspaces
        ADD COLUMN project_directory_id UUID,
        ADD COLUMN project_directory_sandbox_root BOOLEAN NOT NULL DEFAULT TRUE;
    UPDATE platform_workspaces workspace
       SET project_directory_id = directory.id
      FROM platform_project_directories directory
     WHERE directory.project_id = workspace.project_id
       AND directory.tenant_id = workspace.tenant_id
       AND directory.source_repository_id = workspace.source_repository_id
       AND directory.relative_path = '.';
    ALTER TABLE platform_workspaces
        ADD CONSTRAINT uk_platform_workspace_directory_scope
        UNIQUE (id, project_directory_id, project_id, tenant_id),
        ADD CONSTRAINT fk_platform_workspace_project_directory_source
        FOREIGN KEY (project_directory_id, project_id, tenant_id, source_repository_id,
                     project_directory_sandbox_root)
        REFERENCES platform_project_directories(
            id, project_id, tenant_id, source_repository_id, is_sandbox_root),
        ADD CONSTRAINT ck_platform_workspace_directory_sandbox_root
        CHECK (project_directory_sandbox_root),
        ADD CONSTRAINT ck_platform_workspace_project_directory
        CHECK (project_directory_id IS NOT NULL OR state IN ('ARCHIVED', 'CLEANED_UP', 'FAILED'));
    CREATE INDEX idx_platform_workspace_project_directory
        ON platform_workspaces(project_directory_id, created_at DESC)
        WHERE project_directory_id IS NOT NULL;

    ALTER TABLE platform_agent_runs
        ADD COLUMN project_directory_id UUID,
        ADD COLUMN workspace_id UUID;

    WITH checkpoint_binding AS (
        SELECT DISTINCT ON (checkpoint.agent_run_id)
               checkpoint.agent_run_id,
               workspace.id AS workspace_id,
               workspace.project_directory_id
          FROM platform_run_checkpoints checkpoint
          JOIN platform_workspaces workspace
            ON workspace.id::TEXT = (checkpoint.state_snapshot::JSONB ->> 'workspaceId')
         WHERE checkpoint.state_snapshot IS JSON OBJECT
           AND checkpoint.state_snapshot::JSONB ? 'workspaceId'
           AND workspace.project_directory_id IS NOT NULL
         ORDER BY checkpoint.agent_run_id, checkpoint.sequence DESC
    )
    UPDATE platform_agent_runs run
       SET workspace_id = binding.workspace_id,
           project_directory_id = binding.project_directory_id
      FROM checkpoint_binding binding
     WHERE binding.agent_run_id = run.id;

    ALTER TABLE platform_agent_runs
        ADD CONSTRAINT fk_platform_agent_run_project_directory
        FOREIGN KEY (project_directory_id, project_uuid, tenant_id)
        REFERENCES platform_project_directories(id, project_id, tenant_id),
        ADD CONSTRAINT fk_platform_agent_run_workspace_directory
        FOREIGN KEY (workspace_id, project_directory_id, project_uuid, tenant_id)
        REFERENCES platform_workspaces(id, project_directory_id, project_id, tenant_id),
        ADD CONSTRAINT ck_platform_agent_run_workspace_binding
        CHECK ((project_directory_id IS NULL) = (workspace_id IS NULL));
    CREATE INDEX idx_platform_agent_run_workspace
        ON platform_agent_runs(workspace_id, created_at DESC)
        WHERE workspace_id IS NOT NULL;

    COMMENT ON TABLE platform_project_directories IS
        'Project-owned logical source roots/subdirectories; never a client absolute filesystem path';
    COMMENT ON COLUMN platform_agent_runs.workspace_id IS
        'Immutable exact Sandbox Workspace binding for a Project Coding Run';
END
$project_directory_sandbox_hierarchy$;
