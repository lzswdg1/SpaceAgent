DO $user_administration_commands$
BEGIN
    IF to_regclass('public.platform_users') IS NULL
            OR to_regclass('public.platform_user_activity') IS NULL THEN
        RETURN;
    END IF;

    CREATE TABLE platform_user_activation_tokens (
        id              UUID PRIMARY KEY,
        user_id         VARCHAR(36) NOT NULL REFERENCES platform_users(id) ON DELETE CASCADE,
        token_hash      CHAR(64) NOT NULL UNIQUE,
        expires_at      TIMESTAMPTZ NOT NULL,
        consumed_at     TIMESTAMPTZ,
        created_at      TIMESTAMPTZ NOT NULL,
        created_by      UUID NOT NULL
    );

    CREATE UNIQUE INDEX uk_platform_user_activation_active
        ON platform_user_activation_tokens(user_id)
        WHERE consumed_at IS NULL;
    CREATE INDEX idx_platform_user_activation_expiry
        ON platform_user_activation_tokens(expires_at)
        WHERE consumed_at IS NULL;

    CREATE TABLE platform_identity_admin_commands (
        command_id          UUID PRIMARY KEY,
        idempotency_hash    CHAR(64) NOT NULL,
        operation           VARCHAR(80) NOT NULL,
        request_hash        CHAR(64) NOT NULL,
        actor_id            UUID NOT NULL,
        target_user_id      VARCHAR(36),
        state               VARCHAR(24) NOT NULL,
        result_json         JSONB,
        safe_error_code     VARCHAR(80),
        created_at          TIMESTAMPTZ NOT NULL,
        updated_at          TIMESTAMPTZ NOT NULL,
        completed_at        TIMESTAMPTZ,
        CONSTRAINT uk_platform_identity_admin_idempotency
            UNIQUE (operation, idempotency_hash),
        CONSTRAINT ck_platform_identity_admin_state
            CHECK (state IN ('RECEIVED', 'SUCCEEDED', 'FAILED')),
        CONSTRAINT ck_platform_identity_admin_result
            CHECK (result_json IS NULL OR jsonb_typeof(result_json) = 'object')
    );

    CREATE INDEX idx_platform_identity_admin_actor_time
        ON platform_identity_admin_commands(actor_id, created_at DESC);
END
$user_administration_commands$;
