DO $mcp_checkout$ BEGIN
 IF to_regclass('public.platform_mcp_invocation_ledger') IS NULL THEN RETURN; END IF;

 ALTER TABLE platform_mcp_invocation_ledger
  ADD COLUMN checkout_expires_at TIMESTAMPTZ,
  ADD COLUMN checkout_consumed_at TIMESTAMPTZ,
  ADD COLUMN checkout_evidence_hash CHAR(64);

 ALTER TABLE platform_mcp_invocation_ledger
  ADD CONSTRAINT ck_mcp_checkout_grant_evidence CHECK(
   checkout_expires_at IS NULL OR
   (status='SUCCEEDED' AND checkout_evidence_hash IS NOT NULL)),
  ADD CONSTRAINT ck_mcp_checkout_grant_consumed CHECK(
   checkout_consumed_at IS NULL OR
   (checkout_expires_at IS NOT NULL AND result_json IS NULL));

 CREATE INDEX idx_mcp_checkout_grant_expiry
  ON platform_mcp_invocation_ledger(checkout_expires_at)
  WHERE checkout_expires_at IS NOT NULL AND checkout_consumed_at IS NULL;
END $mcp_checkout$;
