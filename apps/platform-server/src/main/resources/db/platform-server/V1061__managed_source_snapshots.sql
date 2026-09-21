DO $managed_source_snapshots$
BEGIN
    IF to_regclass('public.platform_source_repositories') IS NULL
            OR to_regclass('public.platform_project_local_materialization_sessions') IS NULL THEN RETURN; END IF;
    ALTER TABLE platform_source_repositories
        ADD COLUMN materialization_session_id UUID,
        ADD COLUMN snapshot_ref VARCHAR(200),
        ADD COLUMN manifest_sha256 VARCHAR(71),
        ADD COLUMN content_sha256 VARCHAR(71),
        ADD COLUMN finalize_request_id VARCHAR(200);
    ALTER TABLE platform_source_repositories DROP CONSTRAINT ck_platform_source_repository_type;
    ALTER TABLE platform_source_repositories ADD CONSTRAINT ck_platform_source_repository_type
        CHECK (repository_type IN ('LOCAL','MANAGED_SNAPSHOT','GIT','GITHUB','GITLAB','GENERIC'));
    ALTER TABLE platform_source_repositories DROP CONSTRAINT ck_platform_source_repository_authority;
    ALTER TABLE platform_source_repositories ADD CONSTRAINT ck_platform_source_repository_authority CHECK (
        (repository_type='LOCAL' AND workspace_bridge_id IS NOT NULL AND local_root_handle IS NOT NULL
            AND remote_url IS NULL AND github_connection_id IS NULL AND materialization_session_id IS NULL)
        OR (repository_type='GITHUB' AND workspace_bridge_id IS NULL AND local_root_handle IS NULL
            AND remote_url IS NOT NULL AND materialization_session_id IS NULL)
        OR (repository_type='MANAGED_SNAPSHOT' AND workspace_bridge_id IS NULL AND local_root_handle IS NULL
            AND remote_url IS NULL AND github_connection_id IS NULL AND mcp_connection_id IS NULL
            AND mcp_invocation_id IS NULL AND materialization_session_id IS NOT NULL
            AND snapshot_ref ~ '^sources/[0-9a-f-]{36}$'
            AND manifest_sha256 ~ '^sha256:[0-9a-f]{64}$' AND content_sha256 ~ '^sha256:[0-9a-f]{64}$'
            AND finalize_request_id IS NOT NULL)
        OR (repository_type NOT IN ('LOCAL','GITHUB','MANAGED_SNAPSHOT') AND materialization_session_id IS NULL));
    ALTER TABLE platform_source_repositories ADD CONSTRAINT fk_source_materialization_session
        FOREIGN KEY(materialization_session_id) REFERENCES platform_project_local_materialization_sessions(id);
    CREATE UNIQUE INDEX uk_source_materialization_session ON platform_source_repositories(materialization_session_id)
        WHERE materialization_session_id IS NOT NULL;
END
$managed_source_snapshots$;
