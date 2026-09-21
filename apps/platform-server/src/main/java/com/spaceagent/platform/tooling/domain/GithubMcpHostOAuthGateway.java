package com.spaceagent.platform.tooling.domain;

import java.time.Instant;
import java.util.Set;

/** OAuth client boundary for the official GitHub Remote MCP host profile. */
public interface GithubMcpHostOAuthGateway {
    AuthorizationSession begin(
            String state,
            String redirectUri,
            GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata);

    TokenGrant exchange(
            String state,
            String code,
            String redirectUri,
            String codeVerifier,
            GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata);

    TokenGrant refresh(
            String refreshToken,
            Set<String> scopes,
            GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata);

    record AuthorizationSession(
            String authorizationUrl,
            String redirectUri,
            String codeVerifier,
            Set<String> scopes) {
    }

    record TokenGrant(
            String accessToken,
            String refreshToken,
            String tokenType,
            Set<String> scopes,
            Instant issuedAt,
            Instant expiresAt,
            Instant refreshTokenExpiresAt) {
    }
}
