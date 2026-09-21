DO $automation_event_triggers$
BEGIN
    IF to_regclass('public.platform_tenants') IS NULL
            OR to_regclass('public.platform_users') IS NULL
            OR to_regclass('public.platform_agent_definitions') IS NULL THEN
        RETURN;
    END IF;

    CREATE TABLE platform_automation_triggers (
        id UUID PRIMARY KEY,
        lineage_id UUID NOT NULL,
        version INTEGER NOT NULL,
        previous_version_id UUID,
        tenant_id VARCHAR(64) NOT NULL,
        owner_id VARCHAR(36) NOT NULL,
        agent_id VARCHAR(64) NOT NULL,
        description VARCHAR(200) NOT NULL,
        prompt TEXT NOT NULL,
        trigger_type VARCHAR(24) NOT NULL,
        source_config JSONB NOT NULL,
        config_sha256 VARCHAR(71) NOT NULL,
        state VARCHAR(16) NOT NULL,
        revision BIGINT NOT NULL,
        created_at TIMESTAMPTZ NOT NULL,
        activated_at TIMESTAMPTZ,
        archived_at TIMESTAMPTZ,
        CONSTRAINT uk_automation_trigger_lineage_version UNIQUE(lineage_id,version),
        CONSTRAINT uk_automation_trigger_lineage_id UNIQUE(lineage_id,id),
        CONSTRAINT uk_automation_trigger_scope UNIQUE(lineage_id,id,tenant_id,owner_id),
        CONSTRAINT fk_automation_trigger_tenant FOREIGN KEY(tenant_id) REFERENCES platform_tenants(id),
        CONSTRAINT fk_automation_trigger_owner FOREIGN KEY(owner_id) REFERENCES platform_users(id),
        CONSTRAINT fk_automation_trigger_agent FOREIGN KEY(agent_id) REFERENCES platform_agent_definitions(id),
        CONSTRAINT fk_automation_trigger_previous
            FOREIGN KEY(lineage_id,previous_version_id,tenant_id,owner_id)
            REFERENCES platform_automation_triggers(lineage_id,id,tenant_id,owner_id),
        CONSTRAINT ck_automation_trigger_version CHECK(version>0 AND revision>0
            AND ((version=1 AND previous_version_id IS NULL) OR (version>1 AND previous_version_id IS NOT NULL))),
        CONSTRAINT ck_automation_trigger_type CHECK(trigger_type IN
            ('WEBHOOK','REPOSITORY','TASK_COMPLETION','FOLLOW_UP')),
        CONSTRAINT ck_automation_trigger_source CHECK(jsonb_typeof(source_config)='object'),
        CONSTRAINT ck_automation_trigger_hash CHECK(config_sha256~'^sha256:[0-9a-f]{64}$'),
        CONSTRAINT ck_automation_trigger_state CHECK(state IN ('DRAFT','ACTIVE','PAUSED','ARCHIVED')),
        CONSTRAINT ck_automation_trigger_lifecycle CHECK(
            (state='DRAFT' AND activated_at IS NULL AND archived_at IS NULL)
            OR (state IN ('ACTIVE','PAUSED') AND activated_at IS NOT NULL AND archived_at IS NULL)
            OR (state='ARCHIVED' AND archived_at IS NOT NULL))
    );

    CREATE TABLE platform_automation_trigger_subscriptions (
        id UUID PRIMARY KEY,
        trigger_version_id UUID NOT NULL,
        trigger_lineage_id UUID NOT NULL,
        tenant_id VARCHAR(64) NOT NULL,
        owner_id VARCHAR(36) NOT NULL,
        trigger_type VARCHAR(24) NOT NULL,
        source_binding_sha256 VARCHAR(71) NOT NULL,
        state VARCHAR(16) NOT NULL,
        revision BIGINT NOT NULL,
        created_at TIMESTAMPTZ NOT NULL,
        updated_at TIMESTAMPTZ NOT NULL,
        archived_at TIMESTAMPTZ,
        CONSTRAINT uk_automation_subscription_trigger UNIQUE(trigger_version_id),
        CONSTRAINT uk_automation_subscription_scope UNIQUE(id,trigger_lineage_id,tenant_id,owner_id),
        CONSTRAINT fk_automation_subscription_trigger
            FOREIGN KEY(trigger_lineage_id,trigger_version_id,tenant_id,owner_id)
            REFERENCES platform_automation_triggers(lineage_id,id,tenant_id,owner_id),
        CONSTRAINT fk_automation_subscription_tenant FOREIGN KEY(tenant_id) REFERENCES platform_tenants(id),
        CONSTRAINT fk_automation_subscription_owner FOREIGN KEY(owner_id) REFERENCES platform_users(id),
        CONSTRAINT ck_automation_subscription_type CHECK(trigger_type IN
            ('WEBHOOK','REPOSITORY','TASK_COMPLETION','FOLLOW_UP')),
        CONSTRAINT ck_automation_subscription_hash CHECK(source_binding_sha256~'^sha256:[0-9a-f]{64}$'),
        CONSTRAINT ck_automation_subscription_state CHECK(state IN ('ACTIVE','PAUSED','ARCHIVED')),
        CONSTRAINT ck_automation_subscription_lifecycle CHECK(revision>0 AND
            ((state='ARCHIVED' AND archived_at IS NOT NULL) OR (state<>'ARCHIVED' AND archived_at IS NULL)))
    );

    CREATE TABLE platform_automation_trigger_occurrences (
        id UUID PRIMARY KEY,
        trigger_version_id UUID NOT NULL,
        trigger_lineage_id UUID NOT NULL,
        subscription_id UUID NOT NULL,
        tenant_id VARCHAR(64) NOT NULL,
        owner_id VARCHAR(36) NOT NULL,
        source_event_sha256 VARCHAR(71) NOT NULL,
        payload_sha256 VARCHAR(71) NOT NULL,
        state VARCHAR(24) NOT NULL,
        occurred_at TIMESTAMPTZ NOT NULL,
        admitted_at TIMESTAMPTZ,
        revision BIGINT NOT NULL,
        created_at TIMESTAMPTZ NOT NULL,
        updated_at TIMESTAMPTZ NOT NULL,
        CONSTRAINT uk_automation_occurrence_dedup UNIQUE(trigger_lineage_id,source_event_sha256),
        CONSTRAINT uk_automation_occurrence_scope UNIQUE(id,tenant_id,owner_id),
        CONSTRAINT fk_automation_occurrence_trigger
            FOREIGN KEY(trigger_lineage_id,trigger_version_id,tenant_id,owner_id)
            REFERENCES platform_automation_triggers(lineage_id,id,tenant_id,owner_id),
        CONSTRAINT fk_automation_occurrence_subscription
            FOREIGN KEY(subscription_id,trigger_lineage_id,tenant_id,owner_id)
            REFERENCES platform_automation_trigger_subscriptions(id,trigger_lineage_id,tenant_id,owner_id),
        CONSTRAINT ck_automation_occurrence_hash CHECK(source_event_sha256~'^sha256:[0-9a-f]{64}$'
            AND payload_sha256~'^sha256:[0-9a-f]{64}$'),
        CONSTRAINT ck_automation_occurrence_state CHECK(state IN
            ('RECEIVED','READY','BLOCKED','DISPATCHED','SUCCEEDED','FAILED','UNKNOWN','DEAD_LETTERED')),
        CONSTRAINT ck_automation_occurrence_lifecycle CHECK(revision>0 AND
            ((state='RECEIVED' AND admitted_at IS NULL) OR (state<>'RECEIVED' AND admitted_at IS NOT NULL)))
    );

    CREATE TABLE platform_automation_trigger_deliveries (
        id UUID PRIMARY KEY,
        occurrence_id UUID NOT NULL,
        tenant_id VARCHAR(64) NOT NULL,
        owner_id VARCHAR(36) NOT NULL,
        attempt INTEGER NOT NULL,
        state VARCHAR(24) NOT NULL,
        next_attempt_at TIMESTAMPTZ,
        claim_token UUID,
        worker_id VARCHAR(120),
        lease_until TIMESTAMPTZ,
        fencing_token BIGINT NOT NULL DEFAULT 0,
        safe_error_code VARCHAR(64),
        evidence_sha256 VARCHAR(71),
        revision BIGINT NOT NULL,
        created_at TIMESTAMPTZ NOT NULL,
        updated_at TIMESTAMPTZ NOT NULL,
        CONSTRAINT uk_automation_delivery_attempt UNIQUE(occurrence_id,attempt),
        CONSTRAINT uk_automation_delivery_scope UNIQUE(id,occurrence_id,tenant_id,owner_id),
        CONSTRAINT fk_automation_delivery_occurrence FOREIGN KEY(occurrence_id,tenant_id,owner_id)
            REFERENCES platform_automation_trigger_occurrences(id,tenant_id,owner_id),
        CONSTRAINT ck_automation_delivery_state CHECK(state IN
            ('PENDING','CLAIMED','SUCCEEDED','FAILED','UNKNOWN','DEAD_LETTERED')),
        CONSTRAINT ck_automation_delivery_claim CHECK(
            (state='CLAIMED' AND claim_token IS NOT NULL AND worker_id IS NOT NULL AND lease_until IS NOT NULL)
            OR (state<>'CLAIMED' AND claim_token IS NULL AND worker_id IS NULL AND lease_until IS NULL)),
        CONSTRAINT ck_automation_delivery_evidence CHECK(evidence_sha256 IS NULL
            OR evidence_sha256~'^sha256:[0-9a-f]{64}$'),
        CONSTRAINT ck_automation_delivery_counters CHECK(attempt>0 AND fencing_token>=0 AND revision>0)
    );

    CREATE TABLE platform_automation_trigger_dead_letters (
        id UUID PRIMARY KEY,
        occurrence_id UUID NOT NULL UNIQUE,
        delivery_id UUID NOT NULL UNIQUE,
        tenant_id VARCHAR(64) NOT NULL,
        owner_id VARCHAR(36) NOT NULL,
        reason_code VARCHAR(64) NOT NULL,
        evidence_sha256 VARCHAR(71) NOT NULL,
        created_at TIMESTAMPTZ NOT NULL,
        CONSTRAINT fk_automation_dead_letter_delivery FOREIGN KEY(delivery_id,occurrence_id,tenant_id,owner_id)
            REFERENCES platform_automation_trigger_deliveries(id,occurrence_id,tenant_id,owner_id),
        CONSTRAINT ck_automation_dead_letter_reason CHECK(reason_code~'^[A-Z][A-Z0-9_]{0,63}$'),
        CONSTRAINT ck_automation_dead_letter_evidence CHECK(evidence_sha256~'^sha256:[0-9a-f]{64}$')
    );

    CREATE INDEX idx_automation_trigger_owner ON platform_automation_triggers(tenant_id,owner_id,agent_id,lineage_id,version);
    CREATE INDEX idx_automation_occurrence_state ON platform_automation_trigger_occurrences(state,created_at,id);
    CREATE INDEX idx_automation_delivery_due ON platform_automation_trigger_deliveries(next_attempt_at,created_at,id)
        WHERE state IN ('PENDING','FAILED');
    CREATE INDEX idx_automation_dead_letter_owner ON platform_automation_trigger_dead_letters(tenant_id,owner_id,created_at,id);

    COMMENT ON TABLE platform_automation_trigger_occurrences IS
        'Automation-owned event occurrence and immutable source-event digest dedup authority; no raw payload';
    COMMENT ON TABLE platform_automation_trigger_deliveries IS
        'Lease/fence-ready Automation delivery attempts; UNKNOWN is never blindly retried';
END
$automation_event_triggers$;
