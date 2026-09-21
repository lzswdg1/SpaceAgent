DO $observability_views$
BEGIN
    IF to_regclass('public.platform_agent_runs') IS NULL
            OR to_regclass('public.platform_model_call_ledger') IS NULL
            OR to_regclass('public.platform_model_providers') IS NULL
            OR to_regclass('public.platform_agent_definitions') IS NULL
            OR to_regclass('public.platform_tenants') IS NULL
            OR to_regclass('public.platform_conversations') IS NULL
            OR to_regclass('public.platform_messages') IS NULL THEN
        RETURN;
    END IF;

    CREATE VIEW platform_observability_model_calls AS
    SELECT call.id,
           call.agent_run_id,
           run.tenant_id,
           run.owner_id,
           run.agent_id,
           agent.name AS agent_name,
           run.conversation_id,
           call.provider_id,
           COALESCE(provider.name, call.provider_id) AS provider_name,
           call.model_id,
           call.status,
           COALESCE(NULLIF(call.usage_payload ->> 'inputTokens', '')::BIGINT, 0) AS input_tokens,
           COALESCE(NULLIF(call.usage_payload ->> 'outputTokens', '')::BIGINT, 0) AS output_tokens,
           COALESCE(
               NULLIF(call.usage_payload ->> 'cacheReadTokens', '')::BIGINT,
               NULLIF(call.usage_payload ->> 'cached_tokens', '')::BIGINT,
               0) AS cache_read_tokens,
           COALESCE(NULLIF(call.usage_payload ->> 'cacheCreateTokens', '')::BIGINT, 0)
               AS cache_create_tokens,
           NULL::BIGINT AS cost_micros,
           GREATEST(0, ROUND(EXTRACT(EPOCH FROM (call.updated_at - call.created_at)) * 1000))::BIGINT
               AS latency_ms,
           call.created_at,
           call.updated_at
    FROM platform_model_call_ledger call
    JOIN platform_agent_runs run ON run.id = call.agent_run_id
    LEFT JOIN platform_agent_definitions agent ON agent.id = run.agent_id
    LEFT JOIN platform_model_providers provider ON provider.id = call.provider_id;

    CREATE VIEW platform_observability_agents AS
    SELECT id, tenant_id, name, status, created_at, updated_at
    FROM platform_agent_definitions;

    CREATE VIEW platform_observability_organizations AS
    SELECT id, name, status, created_at, updated_at
    FROM platform_tenants;

    CREATE VIEW platform_observability_runs AS
    SELECT run.id,
           COALESCE(run.tenant_id, agent.tenant_id) AS tenant_id,
           run.owner_id,
           run.agent_id,
           COALESCE(agent.name, run.agent_id) AS agent_name,
           run.conversation_id,
           COALESCE(conversation.title, run.conversation_id) AS conversation_name,
           tenant.name AS organization_name,
           run.state,
           run.failure_reason,
           run.project_uuid,
           run.task_uuid,
           run.created_at,
           run.updated_at,
           run.completed_at,
           GREATEST(0, FLOOR(EXTRACT(EPOCH FROM
               (COALESCE(run.completed_at, clock_timestamp()) - run.created_at))))::BIGINT
               AS duration_seconds,
           (SELECT COUNT(*) FROM platform_messages message
             WHERE message.conversation_id = run.conversation_id) AS message_count,
           COALESCE(calls.input_tokens, 0) AS input_tokens,
           COALESCE(calls.output_tokens, 0) AS output_tokens,
           calls.provider_name,
           CASE WHEN run.project_uuid IS NULL THEN 'chat' ELSE 'project' END AS source
    FROM platform_agent_runs run
    LEFT JOIN platform_agent_definitions agent ON agent.id = run.agent_id
    LEFT JOIN platform_tenants tenant ON tenant.id = COALESCE(run.tenant_id, agent.tenant_id)
    LEFT JOIN platform_conversations conversation ON conversation.id = run.conversation_id
    LEFT JOIN LATERAL (
        SELECT SUM(model.input_tokens) AS input_tokens,
               SUM(model.output_tokens) AS output_tokens,
               MAX(model.provider_name) AS provider_name
        FROM platform_observability_model_calls model
        WHERE model.agent_run_id = run.id
    ) calls ON TRUE;

    COMMENT ON VIEW platform_observability_model_calls IS
        'Disposable tenant-scoped usage projection; ModelCallLedger remains authority';
    COMMENT ON VIEW platform_observability_agents IS
        'Disposable Agent inventory projection; AgentDefinition remains authority';
    COMMENT ON VIEW platform_observability_organizations IS
        'Disposable Organization inventory projection; Identity remains authority';
    COMMENT ON VIEW platform_observability_runs IS
        'Disposable Runtime/session projection; AgentRun and Conversation remain authority';
END
$observability_views$;
