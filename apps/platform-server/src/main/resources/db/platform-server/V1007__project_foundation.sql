-- Project-local identities are native UUIDs. Existing Identity keys remain
-- VARCHAR(36) so tenant/user foreign keys match their authoritative tables.
--
-- Some migration-isolation tests intentionally baseline only the Agent schema at
-- V1002. They do not represent a runnable platform and have no Identity authority;
-- keep this unrelated Project migration inert for that partial-schema fixture.
DO $project_foundation$
BEGIN
    IF to_regclass('public.platform_tenants') IS NULL
            OR to_regclass('public.platform_users') IS NULL
            OR to_regclass('public.platform_tenant_memberships') IS NULL THEN
        RETURN;
    END IF;

    CREATE TABLE platform_projects (
        id           UUID PRIMARY KEY,
        tenant_id    VARCHAR(36) NOT NULL,
        owner_id     VARCHAR(36) NOT NULL,
        name         VARCHAR(128) NOT NULL,
        description  TEXT,
        status       VARCHAR(32) NOT NULL,
        created_at   TIMESTAMPTZ NOT NULL,
        updated_at   TIMESTAMPTZ NOT NULL,
        CONSTRAINT uk_platform_projects_tenant_name UNIQUE (tenant_id, name),
        CONSTRAINT ck_platform_projects_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
        CONSTRAINT fk_platform_projects_tenant
            FOREIGN KEY (tenant_id) REFERENCES platform_tenants(id),
        CONSTRAINT fk_platform_projects_owner
            FOREIGN KEY (owner_id) REFERENCES platform_users(id),
        CONSTRAINT fk_platform_projects_owner_tenant_membership
            FOREIGN KEY (tenant_id, owner_id)
            REFERENCES platform_tenant_memberships(tenant_id, user_id)
    );

    CREATE INDEX idx_platform_projects_tenant_updated
        ON platform_projects(tenant_id, updated_at DESC);

    CREATE TABLE platform_project_memberships (
        id          UUID PRIMARY KEY,
        project_id  UUID NOT NULL,
        user_id     VARCHAR(36) NOT NULL,
        role        VARCHAR(32) NOT NULL,
        created_at  TIMESTAMPTZ NOT NULL,
        CONSTRAINT uk_platform_project_memberships_project_user UNIQUE (project_id, user_id),
        CONSTRAINT ck_platform_project_memberships_role
            CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER', 'VIEWER')),
        CONSTRAINT fk_platform_project_memberships_project
            FOREIGN KEY (project_id) REFERENCES platform_projects(id),
        CONSTRAINT fk_platform_project_memberships_user
            FOREIGN KEY (user_id) REFERENCES platform_users(id)
    );

    CREATE UNIQUE INDEX uk_platform_project_memberships_single_owner
        ON platform_project_memberships(project_id)
        WHERE role = 'OWNER';

    CREATE INDEX idx_platform_project_memberships_user
        ON platform_project_memberships(user_id, project_id);

    COMMENT ON TABLE platform_projects IS
        'Tenant-scoped Project identity, lifecycle, and ownership foundation';

    COMMENT ON TABLE platform_project_memberships IS
        'Explicit Project-local authorization roles independent from Tenant roles';
END
$project_foundation$;
