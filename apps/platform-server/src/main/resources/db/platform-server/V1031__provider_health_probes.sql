DO $provider_probes$ BEGIN
 IF to_regclass('public.platform_model_providers') IS NULL THEN RETURN; END IF;

 CREATE TABLE platform_provider_health_probe_jobs(
  provider_id VARCHAR(36) PRIMARY KEY REFERENCES platform_model_providers(id) ON DELETE CASCADE,
  tenant_id VARCHAR(36) NOT NULL REFERENCES platform_tenants(id),
  enabled BOOLEAN NOT NULL,
  next_probe_at TIMESTAMPTZ NOT NULL,
  claim_token UUID,
  claim_owner VARCHAR(160),
  lease_until TIMESTAMPTZ,
  fencing_token BIGINT NOT NULL DEFAULT 0,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  revision BIGINT NOT NULL DEFAULT 1,
  last_started_at TIMESTAMPTZ,
  last_completed_at TIMESTAMPTZ,
  last_error_code VARCHAR(100),
  CONSTRAINT ck_provider_probe_revision CHECK(revision>0 AND fencing_token>=0),
  CONSTRAINT ck_provider_probe_attempt CHECK(attempt_count>=0),
  CONSTRAINT ck_provider_probe_claim CHECK(
   claim_token IS NULL OR (claim_owner IS NOT NULL AND lease_until IS NOT NULL))
 );
 CREATE INDEX idx_provider_probe_due ON platform_provider_health_probe_jobs(next_probe_at)
  WHERE enabled;
 CREATE INDEX idx_provider_probe_lease ON platform_provider_health_probe_jobs(lease_until)
  WHERE claim_token IS NOT NULL;

 CREATE TABLE platform_provider_health_observations(
  id UUID PRIMARY KEY,
  provider_id VARCHAR(36) NOT NULL REFERENCES platform_model_providers(id) ON DELETE CASCADE,
  tenant_id VARCHAR(36) NOT NULL REFERENCES platform_tenants(id),
  source VARCHAR(24) NOT NULL,
  success BOOLEAN NOT NULL,
  connection_status VARCHAR(32) NOT NULL,
  latency_ms INTEGER NOT NULL,
  discovered_models JSONB NOT NULL DEFAULT '[]'::JSONB,
  error_code VARCHAR(100),
  observed_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_provider_observation_source CHECK(source IN('MANUAL','SCHEDULED')),
  CONSTRAINT ck_provider_observation_status CHECK(connection_status IN('ACTIVE','UNHEALTHY')),
  CONSTRAINT ck_provider_observation_latency CHECK(latency_ms>=0),
  CONSTRAINT ck_provider_observation_models CHECK(jsonb_typeof(discovered_models)='array')
 );
 CREATE INDEX idx_provider_observation_history
  ON platform_provider_health_observations(provider_id,observed_at DESC,id);

 INSERT INTO platform_provider_health_probe_jobs(
  provider_id,tenant_id,enabled,next_probe_at,revision)
 SELECT id,tenant_id,enabled,clock_timestamp(),1 FROM platform_model_providers
 ON CONFLICT(provider_id) DO NOTHING;
END $provider_probes$;
