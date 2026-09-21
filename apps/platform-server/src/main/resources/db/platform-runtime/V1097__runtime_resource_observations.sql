DO $runtime_resource_observations$
BEGIN
    IF to_regclass('public.platform_run_events') IS NULL THEN RETURN; END IF;
    ALTER TABLE platform_run_events DROP CONSTRAINT ck_platform_run_event_type;
    ALTER TABLE platform_run_events ADD CONSTRAINT ck_platform_run_event_type CHECK(event_type IN (
        'RUN_CREATED','RUN_STATE_CHANGED','STEP_STARTED','STEP_COMPLETED','STEP_FAILED','CHECKPOINT_CREATED',
        'CURSOR_ADVANCED','ORCHESTRATION_COMMAND_ACCEPTED','WORKER_LEASE_ACQUIRED','WORKER_LEASE_RELEASED',
        'CONTINUATION_ENQUEUED','CONTINUATION_CLAIMED','CONTINUATION_COMPLETED','CONTINUATION_FAILED','RESOURCE_OBSERVED'));
    CREATE UNIQUE INDEX uk_platform_resource_observation_execution
        ON platform_run_events(agent_run_id,(payload->>'executionId')) WHERE event_type='RESOURCE_OBSERVED';
END $runtime_resource_observations$;
