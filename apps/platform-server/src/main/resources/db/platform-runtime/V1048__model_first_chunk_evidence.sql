DO $model_first_chunk_evidence$
BEGIN
    IF to_regclass('public.platform_model_call_ledger') IS NULL THEN RETURN; END IF;

    ALTER TABLE platform_model_call_ledger
        ADD COLUMN first_chunk_at TIMESTAMPTZ,
        ADD COLUMN first_chunk_ms BIGINT;
    ALTER TABLE platform_model_call_ledger
        ADD CONSTRAINT ck_model_call_first_chunk CHECK(
            (first_chunk_at IS NULL) = (first_chunk_ms IS NULL)
            AND (first_chunk_ms IS NULL OR first_chunk_ms >= 0));

    CREATE OR REPLACE VIEW platform_observability_model_calls AS
    SELECT call.id,call.agent_run_id,run.tenant_id,run.owner_id,run.agent_id,
      agent.name AS agent_name,run.conversation_id,call.provider_id,
      COALESCE(provider.name,call.provider_id) AS provider_name,call.model_id,call.status,
      COALESCE(call.input_tokens,NULLIF(call.usage_payload->>'inputTokens','')::BIGINT,0) AS input_tokens,
      COALESCE(call.output_tokens,NULLIF(call.usage_payload->>'outputTokens','')::BIGINT,0) AS output_tokens,
      COALESCE(NULLIF(call.usage_payload->>'cacheReadTokens','')::BIGINT,NULLIF(call.usage_payload->>'cached_tokens','')::BIGINT,0) AS cache_read_tokens,
      COALESCE(NULLIF(call.usage_payload->>'cacheCreateTokens','')::BIGINT,0) AS cache_create_tokens,
      call.cost_micros,GREATEST(0,ROUND(EXTRACT(EPOCH FROM(call.updated_at-call.created_at))*1000))::BIGINT AS latency_ms,
      call.created_at,call.updated_at,call.first_chunk_at,call.first_chunk_ms
    FROM platform_model_call_ledger call JOIN platform_agent_runs run ON run.id=call.agent_run_id
    LEFT JOIN platform_agent_definitions agent ON agent.id=run.agent_id
    LEFT JOIN platform_model_providers provider ON provider.id=call.provider_id;

    CREATE OR REPLACE VIEW platform_trace_summaries AS
    SELECT root.*,CASE root.run_state WHEN 'COMPLETED' THEN 'SUCCESS' WHEN 'FAILED' THEN CASE WHEN root.retry_aborted THEN 'RETRY_ABORTED' ELSE 'ERROR' END WHEN 'CANCELLED' THEN 'ABORTED' ELSE 'RUNNING' END::TEXT AS trace_status,
      first_chunk.first_chunk_ms AS first_token_ms,COALESCE(span.llm_ms,0) AS llm_ms,COALESCE(span.tool_wall_ms,0) AS tool_wall_ms,
      COALESCE(span.tool_duration_sum_ms,0) AS tool_duration_sum_ms,1+COALESCE(span.span_count,0) AS span_count,
      COALESCE(span.llm_turns,0) AS llm_turns,COALESCE(span.tool_calls,0) AS tool_calls,
      COALESCE(span.input_tokens,0) AS input_tokens,COALESCE(span.output_tokens,0) AS output_tokens,
      COALESCE(span.input_tokens,0)+COALESCE(span.output_tokens,0)+COALESCE(span.cache_create_tokens,0)+COALESCE(span.cache_read_tokens,0) AS total_tokens,
      COALESCE(span.cache_create_tokens,0) AS cache_create_tokens,COALESCE(span.cache_read_tokens,0) AS cache_read_tokens,
      CASE WHEN cost.call_count>0 AND cost.call_count=cost.priced_count THEN cost.total_micros::NUMERIC/1000000 ELSE NULL::NUMERIC END AS cost_usd,FALSE AS cost_estimated
    FROM platform_trace_roots root LEFT JOIN LATERAL(
      SELECT COALESCE(SUM(duration_ms) FILTER(WHERE span_type='LLM'),0)::BIGINT AS llm_ms,
       COALESCE(GREATEST(0,ROUND(EXTRACT(EPOCH FROM(MAX(end_time) FILTER(WHERE span_type='TOOL')-MIN(start_time) FILTER(WHERE span_type='TOOL')))*1000)),0)::BIGINT AS tool_wall_ms,
       COALESCE(SUM(duration_ms) FILTER(WHERE span_type='TOOL'),0)::BIGINT AS tool_duration_sum_ms,
       COUNT(*)::INTEGER AS span_count,COUNT(*) FILTER(WHERE span_type='LLM')::INTEGER AS llm_turns,
       COUNT(*) FILTER(WHERE span_type='TOOL')::INTEGER AS tool_calls,
       COALESCE(SUM(input_tokens) FILTER(WHERE span_type='LLM'),0)::BIGINT AS input_tokens,
       COALESCE(SUM(output_tokens) FILTER(WHERE span_type='LLM'),0)::BIGINT AS output_tokens,
       COALESCE(SUM(cache_create_tokens) FILTER(WHERE span_type='LLM'),0)::BIGINT AS cache_create_tokens,
       COALESCE(SUM(cache_read_tokens) FILTER(WHERE span_type='LLM'),0)::BIGINT AS cache_read_tokens
      FROM platform_trace_spans evidence WHERE evidence.trace_id=root.agent_run_id) span ON TRUE
    LEFT JOIN LATERAL(
      SELECT MIN(call.first_chunk_ms)::BIGINT AS first_chunk_ms
      FROM platform_model_call_ledger call
      WHERE call.agent_run_id=root.agent_run_id AND call.first_chunk_ms IS NOT NULL) first_chunk ON TRUE
    LEFT JOIN LATERAL(SELECT COUNT(*) FILTER(WHERE status='SUCCEEDED')::BIGINT AS call_count,
      COUNT(cost_micros) FILTER(WHERE status='SUCCEEDED')::BIGINT AS priced_count,
      COALESCE(SUM(cost_micros) FILTER(WHERE status='SUCCEEDED'),0)::BIGINT AS total_micros
      FROM platform_model_call_ledger call WHERE call.agent_run_id=root.agent_run_id) cost ON TRUE;
END
$model_first_chunk_evidence$;
