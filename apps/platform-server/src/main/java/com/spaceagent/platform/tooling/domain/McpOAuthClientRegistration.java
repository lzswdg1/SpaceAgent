package com.spaceagent.platform.tooling.domain;

import java.util.Set;

public record McpOAuthClientRegistration(
        String id,
        String authorizationServer,
        String clientId,
        String clientSecret,
        McpOAuthClientAuthenticationMethod authenticationMethod,
        Set<String> scopes,
        Set<String> allowedRedirectUris) {
    public McpOAuthClientRegistration {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        allowedRedirectUris = allowedRedirectUris == null
                ? Set.of() : Set.copyOf(allowedRedirectUris);
    }
}
