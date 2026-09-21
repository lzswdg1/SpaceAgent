DO $official_github_remote_mcp$
BEGIN
    IF to_regclass('public.platform_mcp_marketplace_entries') IS NULL THEN
        RETURN;
    END IF;

    UPDATE platform_mcp_marketplace_entries
    SET default_endpoint = 'https://api.githubcopilot.com/mcp/',
        manifest_json = jsonb_build_object(
            'capabilities', jsonb_build_array(
                'repositories', 'issues', 'pull_requests', 'code_search'),
            'oauthProfile', 'host_oauth_2_1_pkce',
            'accountTool', 'get_me',
            'repositoryTool', 'search_repositories',
            'checkoutGrant', 'host_ephemeral_bearer',
            'accountLogin', TRUE,
            'publicUrlDiscovery', TRUE),
        updated_at = clock_timestamp()
    WHERE id = '26000000-0000-4000-8000-000000000001'
      AND slug = 'github';
END $official_github_remote_mcp$;
