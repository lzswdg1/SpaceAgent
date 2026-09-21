DO $model_routing$ BEGIN
 IF to_regclass('public.platform_model_pools') IS NULL THEN RETURN; END IF;
 ALTER TABLE platform_model_pools DROP CONSTRAINT ck_platform_model_pool_routing;
 ALTER TABLE platform_model_pools ADD CONSTRAINT ck_platform_model_pool_routing
  CHECK(routing_strategy IN('PRIORITY','WEIGHTED','COST','LATENCY'));

 CREATE TABLE platform_model_prices(
  id UUID PRIMARY KEY,
  tenant_id VARCHAR(36) NOT NULL REFERENCES platform_tenants(id),
  provider_model_id VARCHAR(36) NOT NULL REFERENCES platform_provider_models(id) ON DELETE CASCADE,
  version INTEGER NOT NULL,
  input_micros_per_million_tokens BIGINT NOT NULL,
  output_micros_per_million_tokens BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  effective_from TIMESTAMPTZ NOT NULL,
  effective_until TIMESTAMPTZ,
  created_by VARCHAR(36) NOT NULL REFERENCES platform_users(id),
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uk_model_price_version UNIQUE(provider_model_id,version),
  CONSTRAINT uk_model_price_effective UNIQUE(provider_model_id,effective_from),
  CONSTRAINT ck_model_price_values CHECK(
   input_micros_per_million_tokens>=0 AND output_micros_per_million_tokens>=0
   AND currency='USD' AND (effective_until IS NULL OR effective_until>effective_from))
 );
 CREATE INDEX idx_model_price_effective
  ON platform_model_prices(provider_model_id,effective_from DESC,effective_until);
END $model_routing$;
