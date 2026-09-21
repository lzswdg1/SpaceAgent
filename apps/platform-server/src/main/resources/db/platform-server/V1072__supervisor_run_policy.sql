CREATE TABLE platform_supervisor_run_policies (
    agent_run_id VARCHAR(36) PRIMARY KEY REFERENCES platform_agent_runs(id) ON DELETE CASCADE,
    tenant_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    kill_switch BOOLEAN NOT NULL,
    provider_candidate_allowed BOOLEAN NOT NULL,
    policy_hash VARCHAR(71) NOT NULL,
    pinned_at TIMESTAMPTZ NOT NULL,
    revision BIGINT NOT NULL,
    CONSTRAINT ck_supervisor_run_policy CHECK (
        mode IN ('DETERMINISTIC','SHADOW','OPT_IN','SELECTED_DEFAULT')
        AND policy_hash ~ '^sha256:[0-9a-f]{64}$' AND revision > 0
        AND (NOT kill_switch OR mode = 'DETERMINISTIC')
        AND (mode <> 'SHADOW' OR NOT provider_candidate_allowed))
);
CREATE INDEX idx_supervisor_run_policy_scope ON platform_supervisor_run_policies(tenant_id, owner_id, pinned_at DESC);
