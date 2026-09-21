CREATE TABLE platform_model_call_ledger (
    id                    VARCHAR(36) PRIMARY KEY,
    agent_run_id          VARCHAR(36) NOT NULL REFERENCES platform_agent_runs(id) ON DELETE CASCADE,
    run_step_id           VARCHAR(36) NOT NULL REFERENCES platform_run_steps(id) ON DELETE CASCADE,
    logical_call_id       VARCHAR(200) NOT NULL,
    request_hash          VARCHAR(64) NOT NULL,
    status                VARCHAR(32) NOT NULL,
    provider_id           VARCHAR(160) NOT NULL,
    model_id              VARCHAR(160) NOT NULL,
    claim_token           UUID,
    claim_owner           VARCHAR(200),
    lease_until           TIMESTAMPTZ,
    revision              BIGINT NOT NULL DEFAULT 1,
    claimed_at            TIMESTAMPTZ,
    provider_request_id   VARCHAR(512),
    response_payload      JSONB,
    usage_payload         JSONB,
    error_code            VARCHAR(160),
    error_summary         VARCHAR(1000),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_platform_model_call_logical UNIQUE (agent_run_id, logical_call_id),
    CONSTRAINT ck_platform_model_call_status CHECK (
        status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED', 'UNKNOWN')
    ),
    CONSTRAINT ck_platform_model_call_revision_positive CHECK (revision > 0),
    CONSTRAINT ck_platform_model_call_running_claim CHECK (
        status <> 'RUNNING'
        OR (
            claim_token IS NOT NULL
            AND claim_owner IS NOT NULL
            AND lease_until IS NOT NULL
            AND claimed_at IS NOT NULL
        )
    )
);

CREATE INDEX idx_platform_model_call_run
    ON platform_model_call_ledger(agent_run_id, created_at, id);

CREATE INDEX idx_platform_model_call_running_lease
    ON platform_model_call_ledger(lease_until)
    WHERE status = 'RUNNING';
