ALTER TABLE admin_sessions ADD COLUMN mfa_verified_at TIMESTAMPTZ;
UPDATE admin_sessions SET mfa_verified_at = created_at WHERE mfa_verified_at IS NULL;
ALTER TABLE admin_sessions ALTER COLUMN mfa_verified_at SET NOT NULL;
CREATE INDEX idx_admin_session_recent_mfa
    ON admin_sessions(principal_id, mfa_verified_at DESC)
    WHERE revoked_at IS NULL;
