DO $inference_budget$ BEGIN
 IF to_regclass('public.platform_model_call_ledger') IS NULL THEN RETURN; END IF;

 CREATE TABLE platform_inference_budget_policies(
  tenant_id VARCHAR(36) PRIMARY KEY REFERENCES platform_tenants(id),enabled BOOLEAN NOT NULL,
  monthly_request_limit BIGINT NOT NULL,monthly_token_limit BIGINT NOT NULL,
  monthly_cost_limit_micros BIGINT NOT NULL,revision BIGINT NOT NULL,
  updated_by VARCHAR(36) NOT NULL REFERENCES platform_users(id),created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_inference_budget_policy CHECK(monthly_request_limit>=0 AND monthly_token_limit>=0 AND monthly_cost_limit_micros>=0 AND revision>0)
 );
 CREATE TABLE platform_inference_budget_periods(
  tenant_id VARCHAR(36) NOT NULL REFERENCES platform_tenants(id),period_start DATE NOT NULL,
  period_end DATE NOT NULL,consumed_requests BIGINT NOT NULL DEFAULT 0,
  reserved_requests BIGINT NOT NULL DEFAULT 0,consumed_tokens BIGINT NOT NULL DEFAULT 0,
  reserved_tokens BIGINT NOT NULL DEFAULT 0,consumed_cost_micros BIGINT NOT NULL DEFAULT 0,
  reserved_cost_micros BIGINT NOT NULL DEFAULT 0,revision BIGINT NOT NULL DEFAULT 1,
  updated_at TIMESTAMPTZ NOT NULL,PRIMARY KEY(tenant_id,period_start),
  CONSTRAINT ck_inference_budget_period CHECK(period_end>period_start AND consumed_requests>=0 AND reserved_requests>=0 AND consumed_tokens>=0 AND reserved_tokens>=0 AND consumed_cost_micros>=0 AND reserved_cost_micros>=0 AND revision>0)
 );
 CREATE TABLE platform_inference_budget_reservations(
  id UUID PRIMARY KEY,tenant_id VARCHAR(36) NOT NULL REFERENCES platform_tenants(id),
  agent_run_id VARCHAR(36) NOT NULL REFERENCES platform_agent_runs(id) ON DELETE CASCADE,
  logical_call_id VARCHAR(240) NOT NULL,provider_id VARCHAR(160) NOT NULL,
  model_id VARCHAR(160) NOT NULL,price_id UUID REFERENCES platform_model_prices(id),
  input_price_micros_per_million BIGINT,output_price_micros_per_million BIGINT,
  reserved_tokens BIGINT NOT NULL,reserved_cost_micros BIGINT,status VARCHAR(24) NOT NULL,
  actual_input_tokens BIGINT,actual_output_tokens BIGINT,actual_cost_micros BIGINT,
  period_start DATE NOT NULL,created_at TIMESTAMPTZ NOT NULL,updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uk_inference_budget_attempt UNIQUE(agent_run_id,logical_call_id),
  CONSTRAINT ck_inference_reservation_status CHECK(status IN('HELD','SETTLED','RELEASED','UNKNOWN')),
  CONSTRAINT ck_inference_reservation_values CHECK(reserved_tokens>=0 AND (reserved_cost_micros IS NULL OR reserved_cost_micros>=0))
 );
 CREATE INDEX idx_inference_budget_reservation_tenant ON platform_inference_budget_reservations(tenant_id,period_start,status);

 ALTER TABLE platform_model_call_ledger ADD COLUMN budget_reservation_id UUID,
  ADD COLUMN price_id UUID,ADD COLUMN input_tokens BIGINT,ADD COLUMN output_tokens BIGINT,
  ADD COLUMN cost_micros BIGINT,ADD COLUMN cost_currency CHAR(3);
 ALTER TABLE platform_model_call_ledger ADD CONSTRAINT fk_model_call_budget_reservation
  FOREIGN KEY(budget_reservation_id) REFERENCES platform_inference_budget_reservations(id),
  ADD CONSTRAINT fk_model_call_price FOREIGN KEY(price_id) REFERENCES platform_model_prices(id),
  ADD CONSTRAINT ck_model_call_accounting CHECK((cost_micros IS NULL AND cost_currency IS NULL) OR (cost_micros>=0 AND cost_currency='USD'));

 CREATE OR REPLACE VIEW platform_observability_model_calls AS
 SELECT call.id,call.agent_run_id,run.tenant_id,run.owner_id,run.agent_id,
  agent.name AS agent_name,run.conversation_id,call.provider_id,
  COALESCE(provider.name,call.provider_id) AS provider_name,call.model_id,call.status,
  COALESCE(call.input_tokens,NULLIF(call.usage_payload->>'inputTokens','')::BIGINT,0) AS input_tokens,
  COALESCE(call.output_tokens,NULLIF(call.usage_payload->>'outputTokens','')::BIGINT,0) AS output_tokens,
  COALESCE(NULLIF(call.usage_payload->>'cacheReadTokens','')::BIGINT,NULLIF(call.usage_payload->>'cached_tokens','')::BIGINT,0) AS cache_read_tokens,
  COALESCE(NULLIF(call.usage_payload->>'cacheCreateTokens','')::BIGINT,0) AS cache_create_tokens,
  call.cost_micros,GREATEST(0,ROUND(EXTRACT(EPOCH FROM(call.updated_at-call.created_at))*1000))::BIGINT AS latency_ms,
  call.created_at,call.updated_at
 FROM platform_model_call_ledger call JOIN platform_agent_runs run ON run.id=call.agent_run_id
 LEFT JOIN platform_agent_definitions agent ON agent.id=run.agent_id
 LEFT JOIN platform_model_providers provider ON provider.id=call.provider_id;

 CREATE OR REPLACE VIEW platform_trace_summaries AS
 SELECT root.*,CASE root.run_state WHEN 'COMPLETED' THEN 'SUCCESS' WHEN 'FAILED' THEN CASE WHEN root.retry_aborted THEN 'RETRY_ABORTED' ELSE 'ERROR' END WHEN 'CANCELLED' THEN 'ABORTED' ELSE 'RUNNING' END::TEXT AS trace_status,
  NULL::BIGINT AS first_token_ms,COALESCE(span.llm_ms,0) AS llm_ms,COALESCE(span.tool_wall_ms,0) AS tool_wall_ms,
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
 LEFT JOIN LATERAL(SELECT COUNT(*) FILTER(WHERE status='SUCCEEDED')::BIGINT AS call_count,
  COUNT(cost_micros) FILTER(WHERE status='SUCCEEDED')::BIGINT AS priced_count,
  COALESCE(SUM(cost_micros) FILTER(WHERE status='SUCCEEDED'),0)::BIGINT AS total_micros
  FROM platform_model_call_ledger call WHERE call.agent_run_id=root.agent_run_id) cost ON TRUE;
END $inference_budget$;
