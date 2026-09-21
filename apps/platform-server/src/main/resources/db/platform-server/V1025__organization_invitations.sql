DO $organization_invitations$
BEGIN
    IF to_regclass('public.platform_tenants') IS NULL
            OR to_regclass('public.platform_users') IS NULL
            OR to_regclass('public.platform_tenant_memberships') IS NULL THEN
        RETURN;
    END IF;

CREATE TABLE platform_organization_invitations (
    id                   VARCHAR(36) PRIMARY KEY,
    organization_id      VARCHAR(36) NOT NULL REFERENCES platform_tenants(id) ON DELETE CASCADE,
    email                VARCHAR(255) NOT NULL,
    invitation_role      VARCHAR(20) NOT NULL,
    status               VARCHAR(20) NOT NULL,
    token_hash           CHAR(64) NOT NULL,
    invited_by_user_id   VARCHAR(36) NOT NULL REFERENCES platform_users(id),
    expires_at           TIMESTAMPTZ NOT NULL,
    accepted_by_user_id  VARCHAR(36) REFERENCES platform_users(id),
    created_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL,
    accepted_at          TIMESTAMPTZ,
    revoked_at           TIMESTAMPTZ,
    CONSTRAINT uk_platform_organization_invitation_token UNIQUE (token_hash),
    CONSTRAINT ck_platform_organization_invitation_email
        CHECK (email = lower(btrim(email)) AND email LIKE '%@%'),
    CONSTRAINT ck_platform_organization_invitation_role
        CHECK (invitation_role IN ('ADMIN', 'MEMBER', 'VIEWER')),
    CONSTRAINT ck_platform_organization_invitation_status
        CHECK (status IN ('PENDING', 'ACCEPTED', 'REVOKED', 'EXPIRED')),
    CONSTRAINT ck_platform_organization_invitation_expiry
        CHECK (expires_at > created_at),
    CONSTRAINT ck_platform_organization_invitation_terminal_shape CHECK (
        (status = 'ACCEPTED' AND accepted_by_user_id IS NOT NULL
            AND accepted_at IS NOT NULL AND revoked_at IS NULL)
        OR (status = 'REVOKED' AND accepted_by_user_id IS NULL
            AND accepted_at IS NULL AND revoked_at IS NOT NULL)
        OR (status IN ('PENDING', 'EXPIRED') AND accepted_by_user_id IS NULL
            AND accepted_at IS NULL AND revoked_at IS NULL)
    )
);

CREATE UNIQUE INDEX uk_platform_organization_invitation_pending_email
    ON platform_organization_invitations (organization_id, email)
    WHERE status = 'PENDING';

CREATE INDEX idx_platform_organization_invitations_list
    ON platform_organization_invitations (organization_id, created_at DESC, id);

CREATE INDEX idx_platform_organization_invitations_expiry
    ON platform_organization_invitations (expires_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_platform_users_external_id_lower
    ON platform_users (lower(external_id));

COMMENT ON COLUMN platform_organization_invitations.token_hash IS
    'SHA-256 digest of a one-time URL-safe token; plaintext is never persisted';
END
$organization_invitations$;
