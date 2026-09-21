-- Keep historical MFA records, but they no longer authorize sessions.
ALTER TABLE admin_sessions ADD COLUMN authenticated_at TIMESTAMPTZ;
UPDATE admin_sessions SET authenticated_at = COALESCE(mfa_verified_at, created_at),
    revoked_at = COALESCE(revoked_at, clock_timestamp());
ALTER TABLE admin_sessions ALTER COLUMN authenticated_at SET NOT NULL;
ALTER TABLE admin_sessions ALTER COLUMN mfa_verified_at DROP NOT NULL;
UPDATE admin_mfa_factors SET status = 'REVOKED', updated_at = clock_timestamp();
UPDATE admin_mfa_challenges SET consumed_at = COALESCE(consumed_at, clock_timestamp());
UPDATE admin_principals SET mfa_required = FALSE, must_change_password = FALSE,
    credential_version = credential_version + 1, updated_at = clock_timestamp();
