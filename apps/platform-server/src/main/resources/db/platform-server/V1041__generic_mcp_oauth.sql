DO $generic_mcp_oauth$
BEGIN
    IF to_regclass('public.platform_mcp_oauth_states') IS NULL THEN
        RETURN;
    END IF;

    ALTER TABLE platform_mcp_oauth_states
        ADD COLUMN connection_revision BIGINT,
        ADD COLUMN provider_session_redacted_at TIMESTAMPTZ;

    UPDATE platform_mcp_oauth_states state
       SET connection_revision = connection.revision
      FROM platform_mcp_connections connection
     WHERE connection.id = state.connection_id;

    UPDATE platform_mcp_oauth_states
       SET encrypted_provider_session = 'REDACTED',
           provider_session_redacted_at = COALESCE(consumed_at, clock_timestamp())
     WHERE consumed_at IS NOT NULL;

    ALTER TABLE platform_mcp_oauth_states
        ALTER COLUMN connection_revision SET NOT NULL,
        ADD CONSTRAINT ck_mcp_oauth_state_connection_revision
            CHECK (connection_revision > 0);

    CREATE INDEX idx_mcp_oauth_connection_revision
        ON platform_mcp_oauth_states(connection_id, connection_revision, created_at DESC);
END
$generic_mcp_oauth$;
