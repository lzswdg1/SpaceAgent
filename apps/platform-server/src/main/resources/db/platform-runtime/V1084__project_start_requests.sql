CREATE TABLE platform_project_start_requests (
    id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    kind VARCHAR(32) NOT NULL CHECK (kind IN ('CODING','GITHUB_ROOT')),
    idempotency_hash CHAR(64) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING','COMPLETED')),
    project_id UUID REFERENCES platform_projects(id) ON DELETE CASCADE,
    result_json JSONB,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    UNIQUE (tenant_id,owner_id,kind,idempotency_hash),
    CHECK ((state='PENDING' AND result_json IS NULL) OR (state='COMPLETED' AND result_json IS NOT NULL))
);
CREATE UNIQUE INDEX uq_project_pending_root_input ON platform_project_start_requests(tenant_id,owner_id,request_hash)
    WHERE kind='GITHUB_ROOT' AND state='PENDING';
CREATE INDEX idx_project_start_project ON platform_project_start_requests(project_id);
CREATE TABLE platform_project_start_request_keys (
    tenant_id VARCHAR(64) NOT NULL,
    owner_id VARCHAR(64) NOT NULL,
    kind VARCHAR(32) NOT NULL,
    idempotency_hash CHAR(64) NOT NULL,
    request_id UUID NOT NULL REFERENCES platform_project_start_requests(id) ON DELETE CASCADE,
    PRIMARY KEY(tenant_id,owner_id,kind,idempotency_hash)
);
CREATE INDEX idx_chat_run_conversation_latest ON platform_agent_runs(tenant_id,owner_id,conversation_id,created_at DESC)
    WHERE project_id IS NULL AND project_uuid IS NULL;
-- Direct Provider/Model Agents are a supported coding binding; null means direct, never an invented pool.
ALTER TABLE platform_project_plan_step_assignments ALTER COLUMN model_pool_id DROP NOT NULL;
