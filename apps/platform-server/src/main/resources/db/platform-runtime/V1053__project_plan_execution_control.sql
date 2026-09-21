DO $project_plan_execution_control$
BEGIN
    IF to_regclass('public.platform_project_plan_executions') IS NULL THEN
        RETURN;
    END IF;

    ALTER TABLE platform_project_plan_executions
        ADD COLUMN desired_state VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
        ADD COLUMN control_reason VARCHAR(240);

    ALTER TABLE platform_project_plan_executions
        DROP CONSTRAINT ck_project_plan_execution_state,
        DROP CONSTRAINT ck_platform_plan_execution_terminal;

    ALTER TABLE platform_project_plan_executions
        ADD CONSTRAINT ck_project_plan_execution_state CHECK(
            state IN ('READY', 'RUNNING', 'PAUSING', 'PAUSED', 'CANCELLING',
                      'CANCELLED', 'COMPLETED', 'FAILED', 'BLOCKED')
        ),
        ADD CONSTRAINT ck_project_plan_execution_desired_state CHECK(
            desired_state IN ('RUNNING', 'PAUSED', 'CANCELLED')
        ),
        ADD CONSTRAINT ck_project_plan_execution_control CHECK(
            (state IN ('READY', 'RUNNING', 'COMPLETED', 'FAILED')
                AND desired_state = 'RUNNING')
            OR (state IN ('PAUSING', 'PAUSED') AND desired_state = 'PAUSED')
            OR (state IN ('CANCELLING', 'CANCELLED') AND desired_state = 'CANCELLED')
            OR state = 'BLOCKED'
        ),
        ADD CONSTRAINT ck_project_plan_execution_control_reason CHECK(
            (state NOT IN ('PAUSING', 'PAUSED', 'CANCELLING', 'CANCELLED')
                OR control_reason IS NOT NULL)
            AND (control_reason IS NULL OR octet_length(control_reason) <= 240)
        ),
        ADD CONSTRAINT ck_project_plan_execution_terminal CHECK(
            (state NOT IN ('COMPLETED', 'FAILED', 'BLOCKED', 'CANCELLED')
                AND completed_at IS NULL)
            OR (state IN ('COMPLETED', 'FAILED', 'BLOCKED', 'CANCELLED')
                AND completed_at IS NOT NULL)
        );

    DROP INDEX IF EXISTS idx_project_plan_execution_active;
    CREATE INDEX idx_project_plan_execution_active
        ON platform_project_plan_executions(
            project_id, owner_id, state, updated_at DESC, created_at DESC, id DESC)
        WHERE state IN ('READY', 'RUNNING', 'PAUSING', 'PAUSED', 'CANCELLING');
END
$project_plan_execution_control$;
