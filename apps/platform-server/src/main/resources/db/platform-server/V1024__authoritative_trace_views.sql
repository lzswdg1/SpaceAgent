DO $authoritative_trace_views$
BEGIN
    IF to_regclass('public.platform_agent_runs') IS NULL
            OR to_regclass('public.platform_run_events') IS NULL
            OR to_regclass('public.platform_model_call_ledger') IS NULL
            OR to_regclass('public.platform_tool_execution_ledger') IS NULL
            OR to_regclass('public.platform_agent_definitions') IS NULL
            OR to_regclass('public.platform_conversations') IS NULL
            OR to_regclass('public.platform_automation_executions') IS NULL
            OR to_regclass('public.platform_run_handoffs') IS NULL
            OR to_regclass('public.platform_agent_delegations') IS NULL
            OR to_regclass('public.platform_agent_reviews') IS NULL
            OR to_regclass('public.platform_artifacts') IS NULL THEN
        RETURN;
    END IF;

    CREATE VIEW platform_trace_roots AS
    SELECT CASE WHEN run.id ~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                THEN lower(run.id)
                ELSE substring(md5('trace:' || run.id), 1, 8) || '-'
                   || substring(md5('trace:' || run.id), 9, 4) || '-4'
                   || substring(md5('trace:' || run.id), 14, 3) || '-8'
                   || substring(md5('trace:' || run.id), 18, 3) || '-'
                   || substring(md5('trace:' || run.id), 21, 12) END AS trace_id,
           run.id AS agent_run_id,
           COALESCE(run.tenant_id, agent.tenant_id) AS tenant_id,
           run.owner_id,
           run.agent_id,
           agent.name AS agent_name,
           run.agent_version_id::TEXT AS agent_version_id,
           run.conversation_id AS session_id,
           conversation.title AS session_name,
           run.project_uuid::TEXT AS project_id,
           run.task_uuid::TEXT AS task_id,
           run.state AS run_state,
           (run.state = 'FAILED' AND run.failure_reason ILIKE '%retry%') AS retry_aborted,
           CASE WHEN run.state = 'FAILED' THEN 'Agent run failed'
                WHEN run.state = 'CANCELLED' THEN 'Agent run cancelled'
                ELSE NULL END AS error_message,
           run.created_at AS start_time,
           run.completed_at AS end_time,
           CASE WHEN run.completed_at IS NULL THEN NULL
                ELSE GREATEST(0, ROUND(EXTRACT(EPOCH FROM
                    (run.completed_at - run.created_at)) * 1000))::BIGINT END AS duration_ms,
           run.created_at,
           run.updated_at,
           jsonb_strip_nulls(jsonb_build_object(
               'agentVersionId', run.agent_version_id,
               'agentRunId', run.id,
               'projectId', run.project_uuid,
               'taskId', run.task_uuid,
               'source', CASE WHEN automation.id IS NOT NULL THEN 'automation'
                              WHEN run.project_uuid IS NOT NULL THEN 'project'
                              ELSE 'chat' END,
               'automationExecutionId', automation.id,
               'automationState', automation.state,
               'automationTriggerType', automation.trigger_type,
               'handoffCount', (SELECT COUNT(*) FROM platform_run_handoffs handoff
                                 WHERE handoff.source_agent_run_id = run.id),
               'delegationCount', (SELECT COUNT(*) FROM platform_agent_delegations delegation
                                    WHERE delegation.parent_run_id = run.id
                                       OR delegation.child_run_id = run.id),
               'reviewCount', (SELECT COUNT(*) FROM platform_agent_reviews review
                                WHERE review.parent_run_id = run.id
                                   OR review.child_run_id = run.id),
               'artifactCount', (SELECT COUNT(*) FROM platform_artifacts artifact
                                  WHERE artifact.agent_run_id = run.id)
           )) AS metadata
    FROM platform_agent_runs run
    LEFT JOIN platform_agent_definitions agent ON agent.id = run.agent_id
    LEFT JOIN platform_conversations conversation ON conversation.id = run.conversation_id
    LEFT JOIN LATERAL (
        SELECT execution.id, execution.state, execution.trigger_type
        FROM platform_automation_executions execution
        WHERE execution.dispatch_run_id = run.id OR execution.agent_run_id = run.id
        ORDER BY execution.created_at DESC, execution.id
        LIMIT 1
    ) automation ON TRUE;

    CREATE VIEW platform_trace_spans AS
    SELECT call.id::TEXT AS source_id,
           call.agent_run_id AS trace_id,
           'LLM'::TEXT AS span_type,
           ('llm:' || call.model_id)::TEXT AS name,
           CASE call.status
               WHEN 'SUCCEEDED' THEN 'SUCCESS'
               WHEN 'FAILED' THEN 'ERROR'
               WHEN 'TIMED_OUT' THEN 'ERROR'
               WHEN 'CANCELLED' THEN 'ABORTED'
               WHEN 'UNKNOWN' THEN 'RETRY_ABORTED'
               ELSE 'RUNNING' END::TEXT AS trace_status,
           CASE WHEN call.status IN ('FAILED','TIMED_OUT','UNKNOWN')
                THEN COALESCE(call.error_code, 'Model call did not complete successfully')
                WHEN call.status = 'CANCELLED' THEN 'Model call cancelled'
                ELSE NULL END AS error_message,
           call.created_at AS start_time,
           CASE WHEN call.status = 'RUNNING' THEN NULL ELSE call.updated_at END AS end_time,
           CASE WHEN call.status = 'RUNNING' THEN NULL ELSE
               GREATEST(0, ROUND(EXTRACT(EPOCH FROM
                   (call.updated_at - call.created_at)) * 1000))::BIGINT END AS duration_ms,
           call.model_id AS model,
           COALESCE(NULLIF(call.usage_payload ->> 'inputTokens', '')::BIGINT, 0) AS input_tokens,
           COALESCE(NULLIF(call.usage_payload ->> 'outputTokens', '')::BIGINT, 0) AS output_tokens,
           COALESCE(NULLIF(call.usage_payload ->> 'cacheCreateTokens', '')::BIGINT, 0)
               AS cache_create_tokens,
           COALESCE(NULLIF(call.usage_payload ->> 'cacheReadTokens', '')::BIGINT,
                    NULLIF(call.usage_payload ->> 'cached_tokens', '')::BIGINT, 0)
               AS cache_read_tokens,
           jsonb_strip_nulls(jsonb_build_object(
               'runStepId', call.run_step_id,
               'logicalCallId', call.logical_call_id,
               'providerId', call.provider_id,
               'modelId', call.model_id,
               'requestHash', call.request_hash,
               'providerRequestId', call.provider_request_id,
               'errorCode', call.error_code,
               'revision', call.revision
           )) AS metadata,
           call.created_at,
           call.updated_at
    FROM platform_model_call_ledger call

    UNION ALL

    SELECT tool.id::TEXT,
           tool.agent_run_id,
           'TOOL',
           tool.tool_name,
           CASE tool.status
               WHEN 'SUCCEEDED' THEN 'SUCCESS'
               WHEN 'FAILED' THEN 'ERROR'
               WHEN 'TIMED_OUT' THEN 'ERROR'
               WHEN 'CANCELLED' THEN 'ABORTED'
               WHEN 'UNKNOWN' THEN 'RETRY_ABORTED'
               ELSE 'RUNNING' END,
           CASE WHEN tool.status = 'UNKNOWN' THEN 'Tool execution outcome is unknown'
                WHEN tool.status IN ('FAILED','TIMED_OUT') THEN 'Tool execution failed'
                WHEN tool.status = 'CANCELLED' THEN 'Tool execution cancelled'
                ELSE NULL END,
           tool.started_at,
           tool.completed_at,
           CASE WHEN tool.completed_at IS NULL THEN NULL ELSE
               GREATEST(0, ROUND(EXTRACT(EPOCH FROM
                   (tool.completed_at - tool.started_at)) * 1000))::BIGINT END,
           NULL::TEXT,
           NULL::BIGINT,
           NULL::BIGINT,
           NULL::BIGINT,
           NULL::BIGINT,
           jsonb_strip_nulls(jsonb_build_object(
               'runStepId', tool.run_step_id,
               'toolName', tool.tool_name,
               'toolCallId', tool.tool_call_id,
               'inputHash', tool.input_hash,
               'revision', tool.revision,
               'resolvedAt', tool.resolved_at,
               'resolutionReason', tool.resolution_reason
           )),
           tool.started_at,
           tool.updated_at
    FROM platform_tool_execution_ledger tool

    UNION ALL

    SELECT event.id::TEXT,
           event.agent_run_id,
           'SYSTEM',
           lower(event.event_type),
           CASE WHEN event.event_type IN ('STEP_FAILED', 'CONTINUATION_FAILED')
                THEN 'ERROR' ELSE 'SUCCESS' END,
           NULL::TEXT,
           event.created_at,
           event.created_at,
           0::BIGINT,
           NULL::TEXT,
           NULL::BIGINT,
           NULL::BIGINT,
           NULL::BIGINT,
           NULL::BIGINT,
           jsonb_build_object(
               'eventType', event.event_type,
               'sequence', event.sequence_number,
               'cursorPhase', event.execution_cursor ->> 'phase'
           ),
           event.created_at,
           event.created_at
    FROM platform_run_events event

    UNION ALL

    SELECT execution.id::TEXT,
           execution.dispatch_run_id,
           'SYSTEM',
           'automation:' || lower(execution.state),
           CASE execution.state
               WHEN 'SUCCEEDED' THEN 'SUCCESS'
               WHEN 'FAILED' THEN 'ERROR'
               WHEN 'UNKNOWN' THEN 'RETRY_ABORTED'
               WHEN 'REJECTED' THEN 'ABORTED'
               WHEN 'EXPIRED' THEN 'ABORTED'
               WHEN 'CANCELLED' THEN 'ABORTED'
               ELSE 'RUNNING' END,
           CASE WHEN execution.state = 'UNKNOWN' THEN 'Automation outcome is unknown'
                WHEN execution.state = 'FAILED' THEN 'Automation execution failed'
                WHEN execution.state IN ('REJECTED','EXPIRED','CANCELLED')
                    THEN 'Automation execution was not authorized or was cancelled'
                ELSE NULL END,
           execution.created_at,
           execution.completed_at,
           CASE WHEN execution.completed_at IS NULL THEN NULL ELSE
               GREATEST(0, ROUND(EXTRACT(EPOCH FROM
                   (execution.completed_at - execution.created_at)) * 1000))::BIGINT END,
           NULL::TEXT,
           NULL::BIGINT,
           NULL::BIGINT,
           NULL::BIGINT,
           NULL::BIGINT,
           jsonb_strip_nulls(jsonb_build_object(
               'automationExecutionId', execution.id,
               'scheduleId', execution.schedule_id,
               'triggerType', execution.trigger_type,
               'scheduledFor', execution.scheduled_for,
               'approvalId', execution.approval_id,
               'continuationId', execution.continuation_id,
               'operationHash', execution.operation_hash
           )),
           execution.created_at,
           execution.updated_at
    FROM platform_automation_executions execution
    WHERE execution.dispatch_run_id IS NOT NULL

    UNION ALL

    SELECT handoff.id::TEXT,
           handoff.source_agent_run_id,
           'SYSTEM',
           'handoff:' || lower(handoff.state),
           CASE WHEN handoff.state = 'COMPLETED' THEN 'SUCCESS'
                WHEN handoff.state IN ('FAILED', 'CANCELLED') THEN 'ERROR'
                ELSE 'RUNNING' END,
           NULL::TEXT,
           handoff.created_at,
           handoff.completed_at,
           CASE WHEN handoff.completed_at IS NULL THEN NULL ELSE
               GREATEST(0, ROUND(EXTRACT(EPOCH FROM
                   (handoff.completed_at - handoff.created_at)) * 1000))::BIGINT END,
           NULL::TEXT,
           NULL::BIGINT,
           NULL::BIGINT,
           NULL::BIGINT,
           NULL::BIGINT,
           jsonb_strip_nulls(jsonb_build_object(
               'handoffId', handoff.id,
               'targetAgentRunId', handoff.target_agent_run_id,
               'testStatus', handoff.test_status,
               'state', handoff.state
           )),
           handoff.created_at,
           COALESCE(handoff.completed_at, handoff.created_at)
    FROM platform_run_handoffs handoff

    UNION ALL

    SELECT (delegation.id::TEXT || ':parent'),
           delegation.parent_run_id,
           'SYSTEM',
           'delegation:' || lower(delegation.state),
           CASE WHEN delegation.state = 'COMPLETED' THEN 'SUCCESS'
                WHEN delegation.state IN ('FAILED', 'CANCELLED') THEN 'ERROR'
                ELSE 'RUNNING' END,
           NULL::TEXT,
           delegation.created_at,
           CASE WHEN delegation.state = 'ACTIVE' THEN NULL ELSE delegation.updated_at END,
           CASE WHEN delegation.state = 'ACTIVE' THEN NULL ELSE
               GREATEST(0, ROUND(EXTRACT(EPOCH FROM
                   (delegation.updated_at - delegation.created_at)) * 1000))::BIGINT END,
           NULL::TEXT,
           NULL::BIGINT,
           NULL::BIGINT,
           NULL::BIGINT,
           NULL::BIGINT,
           jsonb_build_object(
               'delegationId', delegation.id,
               'childRunId', delegation.child_run_id,
               'targetAgentId', delegation.target_agent_id,
               'workspaceId', delegation.workspace_id,
               'state', delegation.state
           ),
           delegation.created_at,
           delegation.updated_at
    FROM platform_agent_delegations delegation

    UNION ALL

    SELECT (delegation.id::TEXT || ':child'),
           delegation.child_run_id,
           'SYSTEM',
           'delegated-from-parent',
           CASE WHEN delegation.state = 'COMPLETED' THEN 'SUCCESS'
                WHEN delegation.state IN ('FAILED', 'CANCELLED') THEN 'ERROR'
                ELSE 'RUNNING' END,
           NULL::TEXT,
           delegation.created_at,
           CASE WHEN delegation.state = 'ACTIVE' THEN NULL ELSE delegation.updated_at END,
           CASE WHEN delegation.state = 'ACTIVE' THEN NULL ELSE
               GREATEST(0, ROUND(EXTRACT(EPOCH FROM
                   (delegation.updated_at - delegation.created_at)) * 1000))::BIGINT END,
           NULL::TEXT,
           NULL::BIGINT,
           NULL::BIGINT,
           NULL::BIGINT,
           NULL::BIGINT,
           jsonb_build_object(
               'delegationId', delegation.id,
               'parentRunId', delegation.parent_run_id,
               'workspaceId', delegation.workspace_id,
               'state', delegation.state
           ),
           delegation.created_at,
           delegation.updated_at
    FROM platform_agent_delegations delegation

    UNION ALL

    SELECT review.id::TEXT,
           review.parent_run_id,
           'SYSTEM',
           'review:' || lower(review.decision),
           CASE WHEN review.decision = 'APPROVED' THEN 'SUCCESS'
                WHEN review.decision = 'CHANGES_REQUESTED' THEN 'ERROR'
                ELSE 'RUNNING' END,
           CASE WHEN review.decision = 'CHANGES_REQUESTED'
                THEN 'Reviewer requested changes' ELSE NULL END,
           review.created_at,
           review.decided_at,
           CASE WHEN review.decided_at IS NULL THEN NULL ELSE
               GREATEST(0, ROUND(EXTRACT(EPOCH FROM
                   (review.decided_at - review.created_at)) * 1000))::BIGINT END,
           NULL::TEXT,
           NULL::BIGINT,
           NULL::BIGINT,
           NULL::BIGINT,
           NULL::BIGINT,
           jsonb_build_object(
               'reviewId', review.id,
               'childRunId', review.child_run_id,
               'reviewerAgentVersionId', review.reviewer_agent_version_id,
               'decision', review.decision,
               'artifactCount', jsonb_array_length(review.artifact_ids_json)
           ),
           review.created_at,
           COALESCE(review.decided_at, review.created_at)
    FROM platform_agent_reviews review

    UNION ALL

    SELECT artifact.id::TEXT,
           artifact.agent_run_id,
           'SYSTEM',
           'artifact:' || lower(artifact.artifact_type),
           'SUCCESS',
           NULL::TEXT,
           artifact.created_at,
           artifact.created_at,
           0::BIGINT,
           NULL::TEXT,
           NULL::BIGINT,
           NULL::BIGINT,
           NULL::BIGINT,
           NULL::BIGINT,
           jsonb_build_object(
               'artifactId', artifact.id,
               'artifactType', artifact.artifact_type,
               'name', artifact.name,
               'contentHash', artifact.content_hash,
               'workspaceId', artifact.workspace_id
           ),
           artifact.created_at,
           artifact.created_at
    FROM platform_artifacts artifact;

    CREATE VIEW platform_trace_summaries AS
    SELECT root.*,
           CASE root.run_state
               WHEN 'COMPLETED' THEN 'SUCCESS'
               WHEN 'FAILED' THEN CASE WHEN root.retry_aborted
                                       THEN 'RETRY_ABORTED' ELSE 'ERROR' END
               WHEN 'CANCELLED' THEN 'ABORTED'
               ELSE 'RUNNING' END::TEXT AS trace_status,
           NULL::BIGINT AS first_token_ms,
           COALESCE(span.llm_ms, 0) AS llm_ms,
           COALESCE(span.tool_wall_ms, 0) AS tool_wall_ms,
           COALESCE(span.tool_duration_sum_ms, 0) AS tool_duration_sum_ms,
           1 + COALESCE(span.span_count, 0) AS span_count,
           COALESCE(span.llm_turns, 0) AS llm_turns,
           COALESCE(span.tool_calls, 0) AS tool_calls,
           COALESCE(span.input_tokens, 0) AS input_tokens,
           COALESCE(span.output_tokens, 0) AS output_tokens,
           COALESCE(span.input_tokens, 0) + COALESCE(span.output_tokens, 0)
             + COALESCE(span.cache_create_tokens, 0) + COALESCE(span.cache_read_tokens, 0)
             AS total_tokens,
           COALESCE(span.cache_create_tokens, 0) AS cache_create_tokens,
           COALESCE(span.cache_read_tokens, 0) AS cache_read_tokens,
           NULL::NUMERIC AS cost_usd,
           FALSE AS cost_estimated
    FROM platform_trace_roots root
    LEFT JOIN LATERAL (
        SELECT COALESCE(SUM(duration_ms) FILTER (WHERE span_type = 'LLM'), 0)::BIGINT AS llm_ms,
               COALESCE(GREATEST(0, ROUND(EXTRACT(EPOCH FROM
                   (MAX(end_time) FILTER (WHERE span_type = 'TOOL')
                    - MIN(start_time) FILTER (WHERE span_type = 'TOOL'))) * 1000)), 0)::BIGINT
                   AS tool_wall_ms,
               COALESCE(SUM(duration_ms) FILTER (WHERE span_type = 'TOOL'), 0)::BIGINT
                   AS tool_duration_sum_ms,
               COUNT(*)::INTEGER AS span_count,
               COUNT(*) FILTER (WHERE span_type = 'LLM')::INTEGER AS llm_turns,
               COUNT(*) FILTER (WHERE span_type = 'TOOL')::INTEGER AS tool_calls,
               COALESCE(SUM(input_tokens) FILTER (WHERE span_type = 'LLM'), 0)::BIGINT
                   AS input_tokens,
               COALESCE(SUM(output_tokens) FILTER (WHERE span_type = 'LLM'), 0)::BIGINT
                   AS output_tokens,
               COALESCE(SUM(cache_create_tokens) FILTER (WHERE span_type = 'LLM'), 0)::BIGINT
                   AS cache_create_tokens,
               COALESCE(SUM(cache_read_tokens) FILTER (WHERE span_type = 'LLM'), 0)::BIGINT
                   AS cache_read_tokens
        FROM platform_trace_spans evidence
        WHERE evidence.trace_id = root.agent_run_id
    ) span ON TRUE;

    COMMENT ON VIEW platform_trace_roots IS
        'Disposable Trace roots; AgentRun remains Runtime authority';
    COMMENT ON VIEW platform_trace_spans IS
        'Redacted disposable span evidence; source Ledgers/Events remain authority';
    COMMENT ON VIEW platform_trace_summaries IS
        'Owner-scoped Trace summary projection; never recovery or billing truth';
END
$authoritative_trace_views$;
