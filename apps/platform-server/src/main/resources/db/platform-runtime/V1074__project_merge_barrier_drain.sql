ALTER TABLE platform_project_plan_merge_barrier_entries
    ADD COLUMN attempt INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN claim_owner VARCHAR(160),
    ADD COLUMN claim_token UUID,
    ADD COLUMN fencing_token BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN lease_until TIMESTAMPTZ,
    ADD COLUMN safe_error_code VARCHAR(120);

ALTER TABLE platform_project_plan_merge_barrier_entries
    DROP CONSTRAINT ck_project_plan_merge_barrier_entry_values;
ALTER TABLE platform_project_plan_merge_barrier_entries
    ADD CONSTRAINT ck_project_plan_merge_barrier_entry_values CHECK (
        apply_index >= 0 AND revision > 0 AND attempt >= 0 AND fencing_token >= 0
        AND state IN ('READY','APPLIED','BLOCKED','UNKNOWN')
        AND ((state='READY' AND completed_at IS NULL)
            OR (state IN ('APPLIED','BLOCKED','UNKNOWN') AND completed_at IS NOT NULL))
        AND ((claim_token IS NULL AND claim_owner IS NULL AND lease_until IS NULL)
            OR (state='READY' AND claim_token IS NOT NULL
                AND claim_owner IS NOT NULL AND lease_until IS NOT NULL))
        AND ((state IN ('BLOCKED','UNKNOWN')) = (safe_error_code IS NOT NULL))
    );

CREATE INDEX idx_project_merge_barrier_drain
    ON platform_project_plan_merge_barrier_entries(
        state, lease_until, updated_at, execution_id, apply_index);
