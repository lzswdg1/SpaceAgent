package com.spaceagent.platform.tooling.domain;

import java.net.URI;

public final class GithubMcpProfiles {
    public static final String OFFICIAL_REMOTE_ENDPOINT = "https://api.githubcopilot.com/mcp/";
    public static final String OFFICIAL_PROFILE = "github-official-remote-v1";

    private GithubMcpProfiles() {
    }

    public static boolean isOfficialRemote(McpConnection connection) {
        return connection != null && isOfficialRemote(connection.endpointUrl());
    }

    public static boolean isOfficialRemote(String endpointUrl) {
        if (endpointUrl == null) return false;
        try {
            URI uri = URI.create(endpointUrl).normalize();
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "api.githubcopilot.com".equalsIgnoreCase(uri.getHost())
                    && "/mcp".equals(path)
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null;
        } catch (RuntimeException error) {
            return false;
        }
    }
}
