DO $governance_approval$
BEGIN
    IF to_regclass('public.platform_tenants') IS NULL
            OR to_regclass('public.platform_users') IS NULL THEN
        RETURN;
    END IF;

    CREATE TABLE platform_governance_policies (
        tenant_id VARCHAR(64) PRIMARY KEY,
        require_coding_file_approval BOOLEAN NOT NULL DEFAULT FALSE,
        require_command_approval BOOLEAN NOT NULL DEFAULT FALSE,
        require_automation_approval BOOLEAN NOT NULL DEFAULT FALSE,
        require_network_approval BOOLEAN NOT NULL DEFAULT FALSE,
        require_source_merge_approval BOOLEAN NOT NULL DEFAULT FALSE,
        separation_of_duties BOOLEAN NOT NULL DEFAULT FALSE,
        approval_ttl_seconds INTEGER NOT NULL DEFAULT 3600,
        revision BIGINT NOT NULL DEFAULT 1,
        updated_by VARCHAR(36) NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
        CONSTRAINT fk_platform_governance_policy_tenant
            FOREIGN KEY (tenant_id) REFERENCES platform_tenants(id),
        CONSTRAINT fk_platform_governance_policy_updated_by
            FOREIGN KEY (updated_by) REFERENCES platform_users(id),
        CONSTRAINT ck_platform_governance_policy_ttl
            CHECK (approval_ttl_seconds BETWEEN 60 AND 604800),
        CONSTRAINT ck_platform_governance_policy_revision CHECK (revision > 0)
    );

    CREATE TABLE platform_approval_requests (
        id UUID PRIMARY KEY,
        tenant_id VARCHAR(64) NOT NULL,
        requested_by VARCHAR(36) NOT NULL,
        action_type VARCHAR(40) NOT NULL,
        resource_type VARCHAR(64) NOT NULL,
        resource_id VARCHAR(255) NOT NULL,
        operation_hash VARCHAR(71) NOT NULL,
        summary VARCHAR(1000) NOT NULL,
        state VARCHAR(20) NOT NULL,
        expires_at TIMESTAMPTZ NOT NULL,
        decided_by VARCHAR(36),
        decided_at TIMESTAMPTZ,
        decision_note VARCHAR(2000),
        consumed_at TIMESTAMPTZ,
        revision BIGINT NOT NULL DEFAULT 1,
        created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
        updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
        CONSTRAINT fk_platform_approval_tenant
            FOREIGN KEY (tenant_id) REFERENCES platform_tenants(id),
        CONSTRAINT fk_platform_approval_requester
            FOREIGN KEY (requested_by) REFERENCES platform_users(id),
        CONSTRAINT fk_platform_approval_decider
            FOREIGN KEY (decided_by) REFERENCES platform_users(id),
        CONSTRAINT ck_platform_approval_action CHECK (action_type IN (
            'CODING_FILE_MUTATION', 'COMMAND_EXECUTION', 'AUTOMATION_TRIGGER',
            'NETWORK_ACCESS', 'SOURCE_MERGE')),
        CONSTRAINT ck_platform_approval_state CHECK (state IN (
            'PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED', 'CONSUMED')),
        CONSTRAINT ck_platform_approval_hash
            CHECK (operation_hash ~ '^sha256:[0-9a-f]{64}$'),
        CONSTRAINT ck_platform_approval_revision CHECK (revision > 0),
        CONSTRAINT ck_platform_approval_decision CHECK (
            (state IN ('APPROVED', 'REJECTED', 'CONSUMED')
                AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
            OR state IN ('PENDING', 'EXPIRED', 'CANCELLED')),
        CONSTRAINT ck_platform_approval_consumption CHECK (
            (state = 'CONSUMED' AND consumed_at IS NOT NULL)
            OR (state <> 'CONSUMED' AND consumed_at IS NULL))
    );

    CREATE UNIQUE INDEX uk_platform_approval_pending_scope
        ON platform_approval_requests (
            tenant_id, requested_by, action_type, resource_type, resource_id, operation_hash)
        WHERE state = 'PENDING';
    CREATE INDEX idx_platform_approval_tenant_state_created
        ON platform_approval_requests (tenant_id, state, created_at DESC);
    CREATE INDEX idx_platform_approval_requester_created
        ON platform_approval_requests (tenant_id, requested_by, created_at DESC);

    COMMENT ON TABLE platform_governance_policies IS
        'Authoritative Organization approval policy; Java Governance owns writes';
    COMMENT ON TABLE platform_approval_requests IS
        'Exact-operation, expiring and one-use approval capability ledger';
END
$governance_approval$;
