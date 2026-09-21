DO $singleton_admin$
BEGIN
    IF (SELECT count(*) FROM admin_principals) > 1 THEN
        RAISE EXCEPTION 'Admin V4 requires exactly one or zero existing SystemAdministrator rows';
    END IF;
END
$singleton_admin$;

ALTER TABLE admin_principals
    ADD COLUMN singleton_slot SMALLINT NOT NULL DEFAULT 1,
    ADD CONSTRAINT ck_admin_principal_singleton_slot CHECK (singleton_slot = 1),
    ADD CONSTRAINT uk_admin_principal_singleton_slot UNIQUE (singleton_slot);

CREATE TABLE admin_break_glass_events (
    request_hash CHAR(64) PRIMARY KEY,
    principal_id UUID NOT NULL REFERENCES admin_principals(id),
    applied_at   TIMESTAMPTZ NOT NULL
);
