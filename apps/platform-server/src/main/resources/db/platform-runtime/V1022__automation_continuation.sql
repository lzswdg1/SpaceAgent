DO $automation_continuation$
BEGIN
    IF to_regclass('public.platform_runtime_continuations') IS NULL THEN
        RETURN;
    END IF;

    ALTER TABLE platform_runtime_continuations
        DROP CONSTRAINT IF EXISTS ck_platform_runtime_continuation_type;
    ALTER TABLE platform_runtime_continuations
        ADD CONSTRAINT ck_platform_runtime_continuation_type
            CHECK (continuation_type IN ('RESUME_RUN', 'AUTOMATION_EXECUTION'));
END
$automation_continuation$;
