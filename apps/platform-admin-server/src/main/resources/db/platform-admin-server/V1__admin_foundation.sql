CREATE TABLE admin_principals (
    id                       UUID PRIMARY KEY,
    login_name               VARCHAR(120) NOT NULL,
    display_name             VARCHAR(120) NOT NULL,
    status                   VARCHAR(24) NOT NULL,
    admin_role               VARCHAR(40) NOT NULL,
    credential_version       BIGINT NOT NULL DEFAULT 1,
    mfa_required             BOOLEAN NOT NULL DEFAULT TRUE,
    last_successful_login_at TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_admin_principal_status
        CHECK (status IN ('ACTIVE', 'SUSPENDED', 'LOCKED', 'DELETED')),
    CONSTRAINT ck_admin_principal_role
        CHECK (admin_role IN ('PLATFORM_SUPER_ADMIN')),
    CONSTRAINT ck_admin_principal_credential_version CHECK (credential_version >= 1)
);

CREATE UNIQUE INDEX uk_admin_principal_login_lower
    ON admin_principals(lower(login_name));

CREATE TABLE admin_credentials (
    principal_id  UUID PRIMARY KEY REFERENCES admin_principals(id) ON DELETE CASCADE,
    password_hash VARCHAR(255) NOT NULL,
    changed_at    TIMESTAMPTZ NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL
);

CREATE TABLE admin_mfa_factors (
    id                         UUID PRIMARY KEY,
    principal_id               UUID NOT NULL REFERENCES admin_principals(id) ON DELETE CASCADE,
    factor_type                VARCHAR(24) NOT NULL,
    secret_ciphertext          TEXT NOT NULL,
    status                     VARCHAR(24) NOT NULL,
    last_accepted_time_step    BIGINT,
    verified_at                TIMESTAMPTZ NOT NULL,
    created_at                 TIMESTAMPTZ NOT NULL,
    updated_at                 TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_admin_mfa_factor_type UNIQUE (principal_id, factor_type),
    CONSTRAINT ck_admin_mfa_factor_type CHECK (factor_type IN ('TOTP')),
    CONSTRAINT ck_admin_mfa_factor_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE TABLE admin_mfa_challenges (
    token_hash    CHAR(64) PRIMARY KEY,
    principal_id  UUID NOT NULL REFERENCES admin_principals(id) ON DELETE CASCADE,
    expires_at    TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    consumed_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_admin_mfa_challenge_attempt CHECK (attempt_count BETWEEN 0 AND 10)
);

CREATE INDEX idx_admin_mfa_challenge_expiry
    ON admin_mfa_challenges(expires_at) WHERE consumed_at IS NULL;

CREATE TABLE admin_sessions (
    id                    UUID PRIMARY KEY,
    principal_id          UUID NOT NULL REFERENCES admin_principals(id) ON DELETE CASCADE,
    refresh_token_hash    CHAR(64) NOT NULL UNIQUE,
    csrf_token_hash       CHAR(64) NOT NULL,
    credential_version    BIGINT NOT NULL,
    expires_at            TIMESTAMPTZ NOT NULL,
    revoked_at            TIMESTAMPTZ,
    replaced_by_hash      CHAR(64),
    created_at            TIMESTAMPTZ NOT NULL,
    last_rotated_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_admin_session_credential_version CHECK (credential_version >= 1)
);

CREATE INDEX idx_admin_session_principal_active
    ON admin_sessions(principal_id, expires_at) WHERE revoked_at IS NULL;

CREATE INDEX idx_admin_session_expiry ON admin_sessions(expires_at);

CREATE TABLE admin_login_attempts (
    id              BIGSERIAL PRIMARY KEY,
    subject_hash    CHAR(64) NOT NULL,
    succeeded       BOOLEAN NOT NULL,
    safe_error_code VARCHAR(64),
    occurred_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_admin_login_attempt_subject_time
    ON admin_login_attempts(subject_hash, occurred_at DESC);

CREATE TABLE admin_commands (
    id                 UUID PRIMARY KEY,
    idempotency_hash   CHAR(64) NOT NULL,
    operation          VARCHAR(80) NOT NULL,
    target_type        VARCHAR(80),
    target_id          VARCHAR(160),
    request_hash       CHAR(64) NOT NULL,
    state              VARCHAR(24) NOT NULL,
    platform_reference VARCHAR(160),
    result_json        JSONB,
    safe_error_code    VARCHAR(64),
    created_by         UUID NOT NULL REFERENCES admin_principals(id),
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    completed_at       TIMESTAMPTZ,
    CONSTRAINT uk_admin_command_idempotency UNIQUE (operation, idempotency_hash),
    CONSTRAINT ck_admin_command_state
        CHECK (state IN ('RECEIVED', 'DISPATCHING', 'ACCEPTED', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT ck_admin_command_result_object
        CHECK (result_json IS NULL OR jsonb_typeof(result_json) = 'object')
);

CREATE INDEX idx_admin_command_actor_time ON admin_commands(created_by, created_at DESC);

CREATE TABLE admin_audit_events (
    id              UUID PRIMARY KEY,
    actor_id        UUID REFERENCES admin_principals(id),
    session_id      UUID,
    action          VARCHAR(100) NOT NULL,
    target_type     VARCHAR(80),
    target_id       VARCHAR(160),
    reason          VARCHAR(500),
    request_id      VARCHAR(120),
    command_id      UUID,
    input_hash      CHAR(64),
    outcome         VARCHAR(24) NOT NULL,
    safe_error_code VARCHAR(64),
    occurred_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_admin_audit_outcome
        CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'DENIED', 'UNKNOWN'))
);

CREATE INDEX idx_admin_audit_actor_time ON admin_audit_events(actor_id, occurred_at DESC);
CREATE INDEX idx_admin_audit_action_time ON admin_audit_events(action, occurred_at DESC);

CREATE TABLE admin_bootstrap_state (
    singleton_id SMALLINT PRIMARY KEY,
    principal_id UUID NOT NULL REFERENCES admin_principals(id),
    completed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_admin_bootstrap_singleton CHECK (singleton_id = 1)
);
