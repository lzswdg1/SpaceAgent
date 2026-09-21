package com.spaceagent.platform.tooling.domain;

import java.time.Instant;
import java.util.Set;

public interface McpOAuthClientGateway {
    AuthorizationSession begin(
            String state,
            String redirectUri,
            McpOAuthClientRegistration registration,
            McpOAuthMetadataGateway.OAuthServerMetadata metadata);

    TokenGrant exchange(
            String state,
            String code,
            String redirectUri,
            String codeVerifier,
            McpOAuthClientRegistration registration,
            McpOAuthMetadataGateway.OAuthServerMetadata metadata);

    TokenGrant refresh(
            String refreshToken,
            Set<String> scopes,
            McpOAuthClientRegistration registration,
            McpOAuthMetadataGateway.OAuthServerMetadata metadata);

    record AuthorizationSession(
            String authorizationUrl,
            String redirectUri,
            String codeVerifier,
            Set<String> scopes) {
        public AuthorizationSession {
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        }
    }

    record TokenGrant(
            String accessToken,
            String refreshToken,
            String tokenType,
            Set<String> scopes,
            Instant issuedAt,
            Instant expiresAt,
            Instant refreshTokenExpiresAt) {
        public TokenGrant {
            scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
        }
    }
}
