CREATE TABLE platform_automation_dispatch_plans (
    id UUID PRIMARY KEY,
    occurrence_id UUID NOT NULL UNIQUE REFERENCES platform_automation_trigger_occurrences(id) ON DELETE CASCADE,
    delivery_id UUID NOT NULL REFERENCES platform_automation_trigger_deliveries(id) ON DELETE CASCADE,
    tenant_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    operation_hash VARCHAR(71) NOT NULL,
    approval_id UUID,
    conversation_id VARCHAR(36) NOT NULL,
    dispatch_run_id VARCHAR(36) NOT NULL,
    continuation_id UUID NOT NULL,
    phase VARCHAR(32) NOT NULL,
    safe_error_code VARCHAR(120),
    revision BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_automation_dispatch_plan CHECK (
        operation_hash ~ '^sha256:[0-9a-f]{64}$' AND revision > 0
        AND phase IN ('RESERVED','CONVERSATION_READY','RUN_READY','CONTINUATION_READY','BLOCKED','UNKNOWN')
        AND ((phase IN ('BLOCKED','UNKNOWN')) = (safe_error_code IS NOT NULL))
    )
);
CREATE INDEX idx_automation_dispatch_plan_scope
    ON platform_automation_dispatch_plans(tenant_id,owner_id,updated_at);
