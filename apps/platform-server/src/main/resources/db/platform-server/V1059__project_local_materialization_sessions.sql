DO $project_local_materialization_sessions$
BEGIN
    IF to_regclass('public.platform_projects') IS NULL
            OR to_regclass('public.platform_local_workspace_bridges') IS NULL
            OR to_regclass('public.platform_tenant_memberships') IS NULL THEN
        RETURN;
    END IF;

    CREATE TABLE platform_project_local_materialization_sessions (
        id                  UUID PRIMARY KEY,
        tenant_id           VARCHAR(36) NOT NULL,
        owner_id            VARCHAR(36) NOT NULL,
        project_id          UUID NOT NULL,
        bridge_id           UUID NOT NULL,
        bridge_device_id    VARCHAR(160) NOT NULL,
        bridge_root_handle  VARCHAR(128) NOT NULL,
        request_id          VARCHAR(200) NOT NULL,
        manifest_sha256     VARCHAR(71),
        state               VARCHAR(24) NOT NULL,
        blocked_code        VARCHAR(120),
        revision            BIGINT NOT NULL,
        expires_at          TIMESTAMPTZ NOT NULL,
        created_at          TIMESTAMPTZ NOT NULL,
        updated_at          TIMESTAMPTZ NOT NULL,
        completed_at        TIMESTAMPTZ,
        CONSTRAINT uk_project_local_materialization_request
            UNIQUE (tenant_id, owner_id, project_id, bridge_id, request_id),
        CONSTRAINT fk_project_local_materialization_project
            FOREIGN KEY (project_id, tenant_id) REFERENCES platform_projects(id, tenant_id),
        CONSTRAINT fk_project_local_materialization_bridge
            FOREIGN KEY (bridge_id, tenant_id, owner_id)
            REFERENCES platform_local_workspace_bridges(id, tenant_id, owner_id),
        CONSTRAINT fk_project_local_materialization_membership
            FOREIGN KEY (tenant_id, owner_id)
            REFERENCES platform_tenant_memberships(tenant_id, user_id),
        CONSTRAINT ck_project_local_materialization_scope
            CHECK (bridge_root_handle ~ '^[A-Za-z0-9_-]{8,128}$'
                AND revision > 0 AND expires_at > created_at),
        CONSTRAINT ck_project_local_materialization_state
            CHECK (
                (state = 'OPEN' AND manifest_sha256 IS NULL AND blocked_code IS NULL AND completed_at IS NULL)
                OR (state IN ('UPLOADING','VERIFIED') AND manifest_sha256 ~ '^sha256:[0-9a-f]{64}$'
                    AND blocked_code IS NULL AND completed_at IS NULL)
                OR (state = 'MATERIALIZED' AND manifest_sha256 ~ '^sha256:[0-9a-f]{64}$'
                    AND blocked_code IS NULL AND completed_at IS NOT NULL)
                OR (state IN ('EXPIRED','CANCELLED') AND blocked_code IS NULL AND completed_at IS NOT NULL
                    AND (manifest_sha256 IS NULL OR manifest_sha256 ~ '^sha256:[0-9a-f]{64}$'))
                OR (state = 'BLOCKED' AND blocked_code IS NOT NULL AND completed_at IS NULL
                    AND (manifest_sha256 IS NULL OR manifest_sha256 ~ '^sha256:[0-9a-f]{64}$'))
            )
    );

    CREATE INDEX idx_project_local_materialization_expiry
        ON platform_project_local_materialization_sessions(state, expires_at, id)
        WHERE state IN ('OPEN','UPLOADING','VERIFIED');
    CREATE INDEX idx_project_local_materialization_project
        ON platform_project_local_materialization_sessions(project_id, owner_id, updated_at DESC, id DESC);

    COMMENT ON TABLE platform_project_local_materialization_sessions IS
        'Project-owned path-free Local Bridge materialization session; bytes remain isolated until verified publication';
END
$project_local_materialization_sessions$;
