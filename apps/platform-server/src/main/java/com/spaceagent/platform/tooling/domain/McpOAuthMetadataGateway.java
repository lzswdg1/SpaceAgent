package com.spaceagent.platform.tooling.domain;

import java.util.Set;

public interface McpOAuthMetadataGateway {
    OAuthServerMetadata resolve(
            McpConnection connection, Set<String> allowedAuthorizationServers);

    record OAuthServerMetadata(
            String resource,
            String authorizationServer,
            String authorizationEndpoint,
            String tokenEndpoint,
            Set<String> scopesSupported,
            Set<String> tokenEndpointAuthenticationMethods) {
        public OAuthServerMetadata {
            scopesSupported = scopesSupported == null ? Set.of() : Set.copyOf(scopesSupported);
            tokenEndpointAuthenticationMethods = tokenEndpointAuthenticationMethods == null
                    ? Set.of() : Set.copyOf(tokenEndpointAuthenticationMethods);
        }
    }
}
