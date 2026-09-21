package com.spaceagent.platform.tooling.domain;

import java.util.Set;

/** Discovers and validates the OAuth metadata advertised by an MCP protected resource. */
public interface GithubMcpOAuthMetadataGateway {
    OAuthServerMetadata resolve(McpConnection connection);

    record OAuthServerMetadata(
            String resource,
            String authorizationServer,
            String authorizationEndpoint,
            String tokenEndpoint,
            Set<String> scopesSupported) {
    }
}
