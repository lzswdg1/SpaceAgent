-- Embeddings are not Agent Runs. Their authority and accounting stay within Inference.
CREATE TABLE platform_embedding_calls (
 id VARCHAR(36) PRIMARY KEY,
 tenant_id VARCHAR(36) NOT NULL REFERENCES platform_tenants(id), actor_id VARCHAR(36) NOT NULL,
 operation_key VARCHAR(160) NOT NULL, request_hash CHAR(64) NOT NULL CHECK(request_hash ~ '^[0-9a-f]{64}$'),
 provider_id VARCHAR(100) NOT NULL, model_id VARCHAR(160) NOT NULL,
 dimensions INT NOT NULL CHECK(dimensions BETWEEN 1 AND 32768),
 price_id UUID REFERENCES platform_model_prices(id), input_rate BIGINT CHECK(input_rate>=0),
 state VARCHAR(20) NOT NULL CHECK(state IN ('PREPARED','DISPATCHED','SUCCEEDED','REJECTED','UNKNOWN')),
 encrypted_response TEXT, input_tokens BIGINT CHECK(input_tokens>=0), cost_micros BIGINT CHECK(cost_micros>=0),
 safe_code VARCHAR(80) CHECK(safe_code ~ '^[A-Z][A-Z0-9_]{0,79}$'),
 deadline TIMESTAMPTZ NOT NULL, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL,
 CHECK((price_id IS NULL AND input_rate IS NULL) OR (price_id IS NOT NULL AND input_rate IS NOT NULL)),
 CHECK(state<>'SUCCEEDED' OR encrypted_response IS NOT NULL),
 CHECK(encrypted_response IS NULL OR octet_length(encrypted_response)<=12000000),
 UNIQUE(tenant_id,actor_id,operation_key), UNIQUE(id,tenant_id)
);
CREATE INDEX idx_embedding_calls_pending ON platform_embedding_calls(deadline) WHERE state IN ('PREPARED','DISPATCHED');
ALTER TABLE platform_inference_budget_reservations ALTER COLUMN agent_run_id DROP NOT NULL;
ALTER TABLE platform_inference_budget_reservations ADD COLUMN embedding_call_id VARCHAR(36);
ALTER TABLE platform_inference_budget_reservations ADD CONSTRAINT fk_embedding_budget_scope
 FOREIGN KEY(embedding_call_id,tenant_id) REFERENCES platform_embedding_calls(id,tenant_id);
ALTER TABLE platform_inference_budget_reservations ADD CONSTRAINT ck_inference_budget_subject
 CHECK((agent_run_id IS NOT NULL AND embedding_call_id IS NULL) OR (agent_run_id IS NULL AND embedding_call_id IS NOT NULL));
CREATE UNIQUE INDEX uk_embedding_budget_reservation ON platform_inference_budget_reservations(embedding_call_id)
 WHERE embedding_call_id IS NOT NULL;
ALTER TABLE platform_knowledge_index_batches DROP CONSTRAINT platform_knowledge_index_batches_state_check;
ALTER TABLE platform_knowledge_index_batches ADD CONSTRAINT platform_knowledge_index_batches_state_check
 CHECK(state IN ('IN_FLIGHT','COMPLETED','REJECTED','UNKNOWN'));
