-- M19 stores repository metadata and opaque local capabilities only. It does not clone
-- repositories or persist any client absolute filesystem path.
DO $source_repository_bridge$
BEGIN
    IF to_regclass('public.platform_projects') IS NULL
            OR to_regclass('public.platform_users') IS NULL
            OR to_regclass('public.platform_tenant_memberships') IS NULL THEN
        RETURN;
    END IF;

    CREATE TABLE platform_github_connections (
        id                       UUID PRIMARY KEY,
        tenant_id                VARCHAR(36) NOT NULL,
        owner_id                 VARCHAR(36) NOT NULL,
        github_account_id        VARCHAR(64) NOT NULL,
        github_account_login     VARCHAR(100) NOT NULL,
        access_token_ciphertext  TEXT NOT NULL,
        scopes_json              JSONB NOT NULL DEFAULT '[]'::JSONB,
        state                    VARCHAR(24) NOT NULL,
        created_at               TIMESTAMPTZ NOT NULL,
        updated_at               TIMESTAMPTZ NOT NULL,
        revoked_at               TIMESTAMPTZ,
        CONSTRAINT uk_platform_github_connection_owner_account
            UNIQUE (tenant_id, owner_id, github_account_id),
        CONSTRAINT uk_platform_github_connection_id_scope
            UNIQUE (id, tenant_id, owner_id),
        CONSTRAINT ck_platform_github_connection_state
            CHECK (state IN ('ACTIVE', 'REVOKED')),
        CONSTRAINT ck_platform_github_connection_scopes
            CHECK (jsonb_typeof(scopes_json) = 'array'),
        CONSTRAINT fk_platform_github_connection_membership
            FOREIGN KEY (tenant_id, owner_id)
            REFERENCES platform_tenant_memberships(tenant_id, user_id)
    );

    CREATE TABLE platform_github_oauth_states (
        state_hash   CHAR(64) PRIMARY KEY,
        tenant_id    VARCHAR(36) NOT NULL,
        owner_id     VARCHAR(36) NOT NULL,
        redirect_uri TEXT NOT NULL,
        expires_at   TIMESTAMPTZ NOT NULL,
        created_at   TIMESTAMPTZ NOT NULL,
        consumed_at  TIMESTAMPTZ,
        CONSTRAINT fk_platform_github_oauth_state_membership
            FOREIGN KEY (tenant_id, owner_id)
            REFERENCES platform_tenant_memberships(tenant_id, user_id)
    );

    CREATE INDEX idx_platform_github_oauth_state_expiry
        ON platform_github_oauth_states(expires_at)
        WHERE consumed_at IS NULL;

    CREATE TABLE platform_local_workspace_bridges (
        id            UUID PRIMARY KEY,
        tenant_id     VARCHAR(36) NOT NULL,
        owner_id      VARCHAR(36) NOT NULL,
        display_name  VARCHAR(120) NOT NULL,
        device_id     VARCHAR(160) NOT NULL,
        root_handle   VARCHAR(128) NOT NULL,
        token_hash    CHAR(64) NOT NULL UNIQUE,
        token_prefix  VARCHAR(12) NOT NULL,
        state         VARCHAR(24) NOT NULL,
        last_seen_at  TIMESTAMPTZ NOT NULL,
        created_at    TIMESTAMPTZ NOT NULL,
        updated_at    TIMESTAMPTZ NOT NULL,
        revoked_at    TIMESTAMPTZ,
        CONSTRAINT uk_platform_local_bridge_root
            UNIQUE (tenant_id, owner_id, device_id, root_handle),
        CONSTRAINT uk_platform_local_bridge_id_scope
            UNIQUE (id, tenant_id, owner_id),
        CONSTRAINT ck_platform_local_bridge_state
            CHECK (state IN ('ACTIVE', 'REVOKED')),
        CONSTRAINT ck_platform_local_bridge_opaque_root
            CHECK (root_handle ~ '^[A-Za-z0-9_-]{8,128}$'),
        CONSTRAINT fk_platform_local_bridge_membership
            FOREIGN KEY (tenant_id, owner_id)
            REFERENCES platform_tenant_memberships(tenant_id, user_id)
    );

    ALTER TABLE platform_projects
        ADD CONSTRAINT uk_platform_projects_id_tenant UNIQUE (id, tenant_id);

    CREATE TABLE platform_source_repositories (
        id                       UUID PRIMARY KEY,
        project_id               UUID NOT NULL,
        tenant_id                VARCHAR(36) NOT NULL,
        github_connection_id     UUID,
        workspace_bridge_id      UUID,
        provider_repository_id   VARCHAR(180) NOT NULL,
        display_name             VARCHAR(200) NOT NULL,
        remote_url               TEXT,
        local_root_handle        VARCHAR(128),
        default_branch           VARCHAR(255) NOT NULL,
        repository_type          VARCHAR(24) NOT NULL,
        state                    VARCHAR(24) NOT NULL,
        visibility               VARCHAR(24) NOT NULL,
        created_by               VARCHAR(36) NOT NULL,
        created_at               TIMESTAMPTZ NOT NULL,
        updated_at               TIMESTAMPTZ NOT NULL,
        CONSTRAINT ck_platform_source_repository_type
            CHECK (repository_type IN ('LOCAL', 'GIT', 'GITHUB', 'GITLAB', 'GENERIC')),
        CONSTRAINT ck_platform_source_repository_state
            CHECK (state IN ('PROVISIONING', 'READY', 'DIRTY', 'FAILED', 'ARCHIVED')),
        CONSTRAINT ck_platform_source_repository_visibility
            CHECK (visibility IN ('PUBLIC', 'PRIVATE', 'INTERNAL', 'LOCAL')),
        CONSTRAINT ck_platform_source_repository_authority CHECK (
            (repository_type = 'LOCAL'
                AND workspace_bridge_id IS NOT NULL
                AND local_root_handle IS NOT NULL
                AND remote_url IS NULL
                AND github_connection_id IS NULL)
            OR
            (repository_type = 'GITHUB'
                AND workspace_bridge_id IS NULL
                AND local_root_handle IS NULL
                AND remote_url IS NOT NULL)
            OR
            (repository_type NOT IN ('LOCAL', 'GITHUB'))
        ),
        CONSTRAINT fk_platform_source_repository_project
            FOREIGN KEY (project_id, tenant_id)
            REFERENCES platform_projects(id, tenant_id),
        CONSTRAINT fk_platform_source_repository_github_connection
            FOREIGN KEY (github_connection_id, tenant_id, created_by)
            REFERENCES platform_github_connections(id, tenant_id, owner_id),
        CONSTRAINT fk_platform_source_repository_bridge
            FOREIGN KEY (workspace_bridge_id, tenant_id, created_by)
            REFERENCES platform_local_workspace_bridges(id, tenant_id, owner_id),
        CONSTRAINT fk_platform_source_repository_creator
            FOREIGN KEY (created_by) REFERENCES platform_users(id)
    );

    CREATE UNIQUE INDEX uk_platform_source_repository_github_active
        ON platform_source_repositories(project_id, provider_repository_id)
        WHERE repository_type = 'GITHUB' AND state <> 'ARCHIVED';
    CREATE UNIQUE INDEX uk_platform_source_repository_local_active
        ON platform_source_repositories(project_id, workspace_bridge_id, local_root_handle)
        WHERE repository_type = 'LOCAL' AND state <> 'ARCHIVED';
    CREATE INDEX idx_platform_source_repository_project
        ON platform_source_repositories(project_id, created_at, id);

    COMMENT ON COLUMN platform_local_workspace_bridges.root_handle IS
        'Opaque client-side capability; never an absolute or server-resolved filesystem path';
    COMMENT ON TABLE platform_source_repositories IS
        'Project-owned source metadata; cloning/worktree provisioning begins in M20';
END
$source_repository_bridge$;
