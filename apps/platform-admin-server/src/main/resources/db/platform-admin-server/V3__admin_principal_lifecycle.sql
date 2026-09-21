ALTER TABLE admin_principals
    ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE admin_recovery_codes (
    id           UUID PRIMARY KEY,
    principal_id UUID NOT NULL REFERENCES admin_principals(id) ON DELETE CASCADE,
    code_hash    CHAR(64) NOT NULL UNIQUE,
    consumed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_admin_recovery_code_principal_active
    ON admin_recovery_codes(principal_id, created_at)
    WHERE consumed_at IS NULL;
