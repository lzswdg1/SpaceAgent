-- Product Organization is the existing Tenant security boundary. This migration adds
-- creator/cleanup lifecycle metadata and enforces one active OWNER without duplicating
-- Tenant state.
DO $organization_lifecycle$
BEGIN
    IF to_regclass('public.platform_tenants') IS NULL
            OR to_regclass('public.platform_users') IS NULL
            OR to_regclass('public.platform_tenant_memberships') IS NULL THEN
        RETURN;
    END IF;

    ALTER TABLE platform_tenants
        ADD COLUMN creator_user_id VARCHAR(36),
        ADD COLUMN deletion_requested_at TIMESTAMPTZ;

    UPDATE platform_tenants tenant
    SET creator_user_id = (
        SELECT membership.user_id
        FROM platform_tenant_memberships membership
        WHERE membership.tenant_id = tenant.id
          AND membership.tenant_role = 'OWNER'
          AND membership.status = 'ACTIVE'
        ORDER BY membership.joined_at, membership.user_id
        LIMIT 1
    )
    WHERE creator_user_id IS NULL;

    ALTER TABLE platform_tenants
        ADD CONSTRAINT fk_platform_tenant_creator
            FOREIGN KEY (creator_user_id) REFERENCES platform_users(id),
        ADD CONSTRAINT ck_platform_tenant_status
            CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DELETING', 'DELETED'));

    CREATE UNIQUE INDEX uk_platform_tenant_single_active_owner
        ON platform_tenant_memberships(tenant_id)
        WHERE tenant_role = 'OWNER' AND status = 'ACTIVE';

    CREATE INDEX idx_platform_tenants_creator
        ON platform_tenants(creator_user_id)
        WHERE creator_user_id IS NOT NULL;

    COMMENT ON COLUMN platform_tenants.creator_user_id IS
        'Unique product Organization creator/current owner; Tenant remains the physical security boundary';
    COMMENT ON COLUMN platform_tenants.deletion_requested_at IS
        'Set when an empty Organization becomes inaccessible and awaits physical cleanup';
END
$organization_lifecycle$;
