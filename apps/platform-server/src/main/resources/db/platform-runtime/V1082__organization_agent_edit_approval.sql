-- M75: temporary cross-owner Agent edit proposals with exact Governance approval.
-- This table is not Agent configuration history: terminal proposal bodies must be erased.

ALTER TABLE platform_approval_requests
    DROP CONSTRAINT ck_platform_approval_action,
    ADD CONSTRAINT ck_platform_approval_action CHECK (action_type IN (
        'CODING_FILE_MUTATION', 'COMMAND_EXECUTION', 'AUTOMATION_TRIGGER',
        'NETWORK_ACCESS', 'SOURCE_MERGE', 'AGENT_CONFIGURATION_CHANGE'));

CREATE TABLE platform_agent_configuration_change_requests (
    id                       UUID PRIMARY KEY,
    approval_id              UUID NOT NULL UNIQUE,
    tenant_id                VARCHAR(64) NOT NULL,
    agent_id                 VARCHAR(36) NOT NULL,
    agent_owner_id           VARCHAR(36) NOT NULL,
    requested_by             VARCHAR(36) NOT NULL,
    base_agent_revision      BIGINT NOT NULL,
    base_config_hash         CHAR(64) NOT NULL,
    proposal_hash            VARCHAR(71) NOT NULL,
    proposal_json            JSONB,
    state                    VARCHAR(20) NOT NULL,
    closed_by                VARCHAR(36),
    decision_note            VARCHAR(2000),
    closed_at                TIMESTAMPTZ,
    applied_agent_revision   BIGINT,
    revision                 BIGINT NOT NULL,
    created_at               TIMESTAMPTZ NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_agent_configuration_change_agent
        FOREIGN KEY (agent_id, tenant_id, agent_owner_id)
        REFERENCES platform_agent_definitions(id, tenant_id, owner_id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_configuration_change_requester
        FOREIGN KEY (tenant_id, requested_by)
        REFERENCES platform_tenant_memberships(tenant_id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_configuration_change_approval
        FOREIGN KEY (approval_id) REFERENCES platform_approval_requests(id) ON DELETE CASCADE,
    CONSTRAINT ck_agent_configuration_change_actor
        CHECK (agent_owner_id <> requested_by),
    CONSTRAINT ck_agent_configuration_change_hashes CHECK (
        base_config_hash ~ '^[0-9a-f]{64}$'
        AND proposal_hash ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_agent_configuration_change_revisions CHECK (
        base_agent_revision > 0 AND revision > 0
        AND (applied_agent_revision IS NULL OR applied_agent_revision > base_agent_revision)),
    CONSTRAINT ck_agent_configuration_change_state CHECK (
        state IN ('PENDING', 'APPLIED', 'REJECTED', 'STALE', 'EXPIRED', 'SUPERSEDED')),
    CONSTRAINT ck_agent_configuration_change_payload CHECK (
        (state = 'PENDING' AND proposal_json IS NOT NULL AND closed_at IS NULL
            AND closed_by IS NULL AND decision_note IS NULL AND applied_agent_revision IS NULL
            AND jsonb_typeof(proposal_json) = 'object'
            AND octet_length(proposal_json::TEXT) <= 131072)
        OR
        (state <> 'PENDING' AND proposal_json IS NULL AND closed_at IS NOT NULL
            AND ((state = 'APPLIED' AND closed_by IS NOT NULL
                    AND applied_agent_revision IS NOT NULL)
                OR (state <> 'APPLIED' AND applied_agent_revision IS NULL))))
);

CREATE UNIQUE INDEX uk_agent_configuration_change_pending_requester
    ON platform_agent_configuration_change_requests(tenant_id, agent_id, requested_by)
    WHERE state = 'PENDING';
CREATE INDEX idx_agent_configuration_change_agent_created
    ON platform_agent_configuration_change_requests(tenant_id, agent_id, created_at DESC, id DESC);
CREATE INDEX idx_agent_configuration_change_pending_approval
    ON platform_agent_configuration_change_requests(tenant_id, approval_id)
    WHERE state = 'PENDING';

COMMENT ON TABLE platform_agent_configuration_change_requests IS
    'Temporary cross-owner Agent edit proposals; terminal payloads are erased and are not Agent versions';
