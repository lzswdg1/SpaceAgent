ALTER TABLE platform_tool_execution_ledger
    ADD COLUMN IF NOT EXISTS claim_token UUID,
    ADD COLUMN IF NOT EXISTS claim_owner VARCHAR(200),
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS claimed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS reconciliation_evidence JSONB,
    ADD COLUMN IF NOT EXISTS resolved_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS resolved_by VARCHAR(200),
    ADD COLUMN IF NOT EXISTS resolution_reason TEXT;

ALTER TABLE platform_tool_execution_ledger
    ALTER COLUMN input_hash SET NOT NULL;

-- Preserve terminal outcomes exactly. Their new metadata is audit-only and does not
-- create a new executable claim.
UPDATE platform_tool_execution_ledger
   SET updated_at = COALESCE(completed_at, started_at) AT TIME ZONE 'UTC'
 WHERE status IN ('SUCCEEDED', 'FAILED', 'TIMED_OUT', 'CANCELLED');

-- Pre-M10 non-terminal rows do not have a trustworthy claim token, owner, lease, or
-- fencing revision. They may already have crossed the external side-effect boundary,
-- so the only safe migration is UNKNOWN. They must never be reset to executable state.
UPDATE platform_tool_execution_ledger
   SET status = 'UNKNOWN',
       claim_token = NULL,
       error = COALESCE(error, 'legacy non-terminal tool execution requires reconciliation'),
       revision = revision + 1,
       updated_at = clock_timestamp(),
       reconciliation_evidence = COALESCE(
           reconciliation_evidence,
           jsonb_build_object(
               'reason', 'legacy non-terminal tool execution migrated conservatively',
               'externalExecutionReference', NULL,
               'details', jsonb_build_object('migration', 'V1001')
           )
       )
 WHERE status IN ('PENDING', 'RUNNING');

ALTER TABLE platform_tool_execution_ledger
    ADD CONSTRAINT ck_platform_tool_ledger_revision_positive
        CHECK (revision > 0),
    ADD CONSTRAINT ck_platform_tool_ledger_running_claim
        CHECK (
            status <> 'RUNNING'
            OR (
                claim_token IS NOT NULL
                AND claim_owner IS NOT NULL
                AND lease_until IS NOT NULL
                AND claimed_at IS NOT NULL
            )
        );

CREATE INDEX IF NOT EXISTS idx_platform_tool_ledger_running_lease
    ON platform_tool_execution_ledger(lease_until)
    WHERE status = 'RUNNING';
