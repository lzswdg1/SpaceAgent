DO $system_administration_read_model$
BEGIN
    IF to_regclass('public.platform_users') IS NULL
            OR to_regclass('public.platform_refresh_tokens') IS NULL THEN
        RETURN;
    END IF;

    ALTER TABLE platform_users
        ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
        ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
        ADD COLUMN deletion_requested_at TIMESTAMPTZ,
        ADD COLUMN deleted_at TIMESTAMPTZ;

    ALTER TABLE platform_users
        ADD CONSTRAINT ck_platform_user_status CHECK (
            status IN ('PENDING_ACTIVATION', 'ACTIVE', 'SUSPENDED', 'DELETION_PENDING', 'DELETED')
        ),
        ADD CONSTRAINT ck_platform_user_deleted_at CHECK (
            (status = 'DELETED') = (deleted_at IS NOT NULL)
        );

    CREATE INDEX idx_platform_users_admin_page
        ON platform_users(status, created_at DESC, id);

    CREATE TABLE platform_user_activity (
        user_id                    VARCHAR(36) PRIMARY KEY
            REFERENCES platform_users(id) ON DELETE CASCADE,
        last_login_at              TIMESTAMPTZ,
        last_seen_at               TIMESTAMPTZ,
        successful_login_count     BIGINT NOT NULL DEFAULT 0,
        last_client_type           VARCHAR(24),
        last_ip_hash               CHAR(64),
        last_user_agent_hash       CHAR(64),
        updated_at                 TIMESTAMPTZ NOT NULL,
        CONSTRAINT ck_platform_user_activity_login_count
            CHECK (successful_login_count >= 0),
        CONSTRAINT ck_platform_user_activity_client_type
            CHECK (last_client_type IS NULL OR last_client_type IN ('WEB', 'CLI', 'API', 'UNKNOWN'))
    );

    CREATE INDEX idx_platform_user_activity_last_seen
        ON platform_user_activity(last_seen_at DESC)
        WHERE last_seen_at IS NOT NULL;

    CREATE TABLE platform_auth_events (
        id                  UUID PRIMARY KEY,
        user_id             VARCHAR(36) REFERENCES platform_users(id) ON DELETE SET NULL,
        subject_hash        CHAR(64) NOT NULL,
        event_type          VARCHAR(32) NOT NULL,
        client_type         VARCHAR(24) NOT NULL,
        ip_hash             CHAR(64),
        user_agent_hash     CHAR(64),
        safe_error_code     VARCHAR(80),
        occurred_at         TIMESTAMPTZ NOT NULL,
        CONSTRAINT ck_platform_auth_event_type
            CHECK (event_type IN ('LOGIN_SUCCEEDED', 'LOGIN_FAILED')),
        CONSTRAINT ck_platform_auth_event_client_type
            CHECK (client_type IN ('WEB', 'CLI', 'API', 'UNKNOWN')),
        CONSTRAINT ck_platform_auth_event_outcome
            CHECK ((event_type = 'LOGIN_SUCCEEDED') = (safe_error_code IS NULL))
    );

    CREATE INDEX idx_platform_auth_event_time
        ON platform_auth_events(occurred_at DESC, id);
    CREATE INDEX idx_platform_auth_event_user_time
        ON platform_auth_events(user_id, occurred_at DESC)
        WHERE user_id IS NOT NULL;

    IF to_regclass('public.platform_model_providers') IS NOT NULL THEN
        ALTER TABLE platform_model_providers
            ADD COLUMN secret_hint VARCHAR(40),
            ADD COLUMN secret_fingerprint CHAR(64),
            ADD COLUMN secret_key_version VARCHAR(32);
        CREATE INDEX idx_platform_model_provider_secret_fingerprint
            ON platform_model_providers(secret_fingerprint)
            WHERE secret_fingerprint IS NOT NULL;
    END IF;
END
$system_administration_read_model$;
