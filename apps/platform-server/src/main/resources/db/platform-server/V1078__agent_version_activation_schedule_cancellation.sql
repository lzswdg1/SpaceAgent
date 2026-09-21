DO $agent_version_activation_schedule_cancellation$
BEGIN
    IF to_regclass('platform_agent_version_activation_schedules') IS NULL THEN
        RETURN;
    END IF;

    IF EXISTS (
        SELECT 1
          FROM platform_agent_version_activation_schedules
         WHERE state = 'CANCELLED'
    ) THEN
        RAISE EXCEPTION
            'V1078 cannot fabricate cancellation evidence for an existing CANCELLED activation schedule';
    END IF;

    ALTER TABLE platform_agent_version_activation_schedules
        ADD COLUMN cancelled_by VARCHAR(36),
        ADD COLUMN cancelled_at TIMESTAMPTZ,
        ADD COLUMN cancellation_reason_sha256 VARCHAR(71),
        ADD CONSTRAINT fk_agent_activation_cancelled_by
            FOREIGN KEY (cancelled_by, tenant_id)
            REFERENCES platform_users(id, tenant_id),
        ADD CONSTRAINT ck_agent_activation_cancellation_evidence CHECK (
            (
                state = 'CANCELLED'
                AND cancelled_by IS NOT NULL
                AND cancelled_at IS NOT NULL
                AND completed_at = cancelled_at
                AND cancellation_reason_sha256 ~ '^sha256:[0-9a-f]{64}$'
            )
            OR
            (
                state <> 'CANCELLED'
                AND cancelled_by IS NULL
                AND cancelled_at IS NULL
                AND cancellation_reason_sha256 IS NULL
            )
        );
END $agent_version_activation_schedule_cancellation$;
