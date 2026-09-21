package com.spaceagent.platform.tooling.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.domain.GithubMcpHostOAuthGateway;
import com.spaceagent.platform.tooling.domain.GithubMcpOAuthMetadataGateway;
import com.spaceagent.platform.tooling.domain.GithubMcpProfiles;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpConnectionSecretCipher;
import com.spaceagent.platform.tooling.domain.McpConnectionState;
import com.spaceagent.platform.tooling.domain.McpMarketplaceRepository;
import com.spaceagent.platform.tooling.domain.McpOAuthClientGateway;
import com.spaceagent.platform.tooling.domain.McpOAuthClientRegistration;
import com.spaceagent.platform.tooling.domain.McpOAuthClientRegistrationProvider;
import com.spaceagent.platform.tooling.domain.McpOAuthMetadataGateway;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class McpConnectionAuthorizationService {
    private static final long REFRESH_SKEW_SECONDS = 60;

    private final McpMarketplaceRepository repository;
    private final McpConnectionSecretCipher cipher;
    private final GithubMcpHostOAuthGateway githubOauth;
    private final McpOAuthClientGateway genericOauth;
    private final McpOAuthClientRegistrationProvider registrations;
    private final ObjectMapper json;
    private final TimeProvider time;

    @Autowired
    public McpConnectionAuthorizationService(
            McpMarketplaceRepository repository,
            McpConnectionSecretCipher cipher,
            GithubMcpHostOAuthGateway githubOauth,
            McpOAuthClientGateway genericOauth,
            McpOAuthClientRegistrationProvider registrations,
            ObjectMapper json,
            TimeProvider time) {
        this.repository = repository;
        this.cipher = cipher;
        this.githubOauth = githubOauth;
        this.genericOauth = genericOauth;
        this.registrations = registrations;
        this.json = json;
        this.time = time;
    }

    public McpConnectionAuthorizationService(
            McpMarketplaceRepository repository,
            McpConnectionSecretCipher cipher,
            GithubMcpHostOAuthGateway githubOauth,
            ObjectMapper json,
            TimeProvider time) {
        this(repository, cipher, githubOauth, null, null, json, time);
    }

    public AuthorizedConnection authorize(McpConnection connection) {
        Map<String, String> authorization = read(connection);
        OAuthProfile profile = profile(connection, authorization);
        if (profile == OAuthProfile.NONE) {
            return new AuthorizedConnection(connection, Map.copyOf(authorization));
        }
        Instant now = time.now();
        Instant expiresAt = instant(authorization.get("expires_at"), profile);
        if (expiresAt == null || expiresAt.isAfter(now.plusSeconds(REFRESH_SKEW_SECONDS))) {
            requireAccessToken(authorization, profile);
            return new AuthorizedConnection(connection, Map.copyOf(authorization));
        }
        String refreshToken = authorization.get("refresh_token");
        Instant refreshExpiresAt = instant(
                authorization.get("refresh_token_expires_at"), profile);
        if (refreshToken == null || refreshToken.isBlank()
                || (refreshExpiresAt != null && !refreshExpiresAt.isAfter(now))) {
            throw reauthorizationRequired(profile);
        }

        Grant grant = refresh(profile, refreshToken, authorization);
        Map<String, String> refreshed = merge(authorization, grant);
        String encrypted = cipher.encrypt(write(refreshed));
        if (repository.refreshConnectionAuthorizationIfUnchanged(
                connection.id(), connection.revision(), connection.encryptedAuthJson(),
                encrypted, now)) {
            return new AuthorizedConnection(withAuthorization(connection, encrypted, now),
                    Map.copyOf(refreshed));
        }

        McpConnection winner = repository.findConnection(connection.id())
                .filter(value -> value.state() != McpConnectionState.REVOKED)
                .orElseThrow(() -> reauthorizationRequired(profile));
        Map<String, String> winnerAuthorization = read(winner);
        OAuthProfile winnerProfile = profile(winner, winnerAuthorization);
        if (winnerProfile != profile) throw reauthorizationRequired(profile);
        Instant winnerExpiry = instant(winnerAuthorization.get("expires_at"), profile);
        if (winnerExpiry != null
                && !winnerExpiry.isAfter(now.plusSeconds(REFRESH_SKEW_SECONDS))) {
            throw reauthorizationRequired(profile);
        }
        requireAccessToken(winnerAuthorization, profile);
        return new AuthorizedConnection(winner, Map.copyOf(winnerAuthorization));
    }

    private Grant refresh(
            OAuthProfile profile,
            String refreshToken,
            Map<String, String> authorization) {
        try {
            if (profile == OAuthProfile.GITHUB) {
                GithubMcpHostOAuthGateway.TokenGrant value = githubOauth.refresh(
                        refreshToken, scopes(authorization.get("scope")),
                        githubMetadata(authorization));
                return new Grant(
                        value.accessToken(), value.refreshToken(), value.tokenType(),
                        value.scopes(), value.issuedAt(), value.expiresAt(),
                        value.refreshTokenExpiresAt());
            }
            McpOAuthClientRegistration registration = registrations
                    .findById(required(
                            authorization.get("oauth_client_registration_id"), profile))
                    .filter(value -> value.authorizationServer().equals(required(
                            authorization.get("oauth_authorization_server"), profile)))
                    .orElseThrow(() -> reauthorizationRequired(profile));
            McpOAuthClientGateway.TokenGrant value = genericOauth.refresh(
                    refreshToken, scopes(authorization.get("scope")), registration,
                    genericMetadata(authorization));
            return new Grant(
                    value.accessToken(), value.refreshToken(), value.tokenType(),
                    value.scopes(), value.issuedAt(), value.expiresAt(),
                    value.refreshTokenExpiresAt());
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw reauthorizationRequired(profile);
        }
    }

    private Map<String, String> merge(Map<String, String> current, Grant grant) {
        Map<String, String> value = new LinkedHashMap<>(current);
        value.put("access_token", required(grant.accessToken(), profileFromMap(current)));
        String tokenType = grant.tokenType() == null ? "Bearer" : grant.tokenType();
        if (!"Bearer".equalsIgnoreCase(tokenType)) {
            throw reauthorizationRequired(profileFromMap(current));
        }
        value.put("token_type", "Bearer");
        if (grant.refreshToken() != null && !grant.refreshToken().isBlank()) {
            value.put("refresh_token",
                    required(grant.refreshToken(), profileFromMap(current)));
        }
        if (grant.scopes() != null && !grant.scopes().isEmpty()) {
            value.put("scope", String.join(" ", validatedScopes(
                    grant.scopes(), profileFromMap(current))));
        }
        put(value, "issued_at", grant.issuedAt());
        put(value, "expires_at", grant.expiresAt());
        put(value, "refresh_token_expires_at", grant.refreshTokenExpiresAt());
        return value;
    }

    private static McpConnection withAuthorization(
            McpConnection connection, String encrypted, Instant now) {
        return new McpConnection(
                connection.id(), connection.installationId(), connection.tenantId(),
                connection.managedBy(), connection.endpointUrl(), encrypted,
                connection.authType(), connection.state(), connection.externalAccountId(),
                connection.externalAccountName(), connection.revision(),
                connection.createdAt(), now, connection.revokedAt());
    }

    private OAuthProfile profile(
            McpConnection connection, Map<String, String> authorization) {
        String value = authorization.get("oauth_profile");
        if (GithubMcpProfiles.isOfficialRemote(connection)
                && GithubMcpProfiles.OFFICIAL_PROFILE.equals(value)) {
            return OAuthProfile.GITHUB;
        }
        if (McpOAuthApplicationService.PROFILE.equals(value)) {
            return OAuthProfile.GENERIC;
        }
        return OAuthProfile.NONE;
    }

    private static OAuthProfile profileFromMap(Map<String, String> authorization) {
        return McpOAuthApplicationService.PROFILE.equals(authorization.get("oauth_profile"))
                ? OAuthProfile.GENERIC : OAuthProfile.GITHUB;
    }

    private Map<String, String> read(McpConnection connection) {
        try {
            return json.readValue(
                    cipher.decrypt(connection.encryptedAuthJson()),
                    new TypeReference<Map<String, String>>() { });
        } catch (Exception error) {
            throw new IllegalStateException("Unable to read MCP auth reference");
        }
    }

    private String write(Map<String, String> value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to write MCP auth reference");
        }
    }

    private static Set<String> scopes(String value) {
        if (value == null || value.isBlank()) return Set.of();
        return Arrays.stream(value.trim().split("[ ,]+"))
                .filter(scope -> scope.matches("[A-Za-z0-9:._/-]{1,100}"))
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> validatedScopes(
            Set<String> scopes, OAuthProfile profile) {
        if (scopes.size() > 100) throw reauthorizationRequired(profile);
        Set<String> result = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (scope == null || !scope.matches("[A-Za-z0-9:._/-]{1,100}")) {
                throw reauthorizationRequired(profile);
            }
            result.add(scope);
        }
        return Set.copyOf(result);
    }

    private static GithubMcpOAuthMetadataGateway.OAuthServerMetadata githubMetadata(
            Map<String, String> authorization) {
        return new GithubMcpOAuthMetadataGateway.OAuthServerMetadata(
                required(authorization.get("oauth_resource"), OAuthProfile.GITHUB),
                required(authorization.get("oauth_authorization_server"), OAuthProfile.GITHUB),
                required(authorization.get("oauth_authorization_endpoint"), OAuthProfile.GITHUB),
                required(authorization.get("oauth_token_endpoint"), OAuthProfile.GITHUB),
                scopes(authorization.get("oauth_scopes_supported")));
    }

    private static McpOAuthMetadataGateway.OAuthServerMetadata genericMetadata(
            Map<String, String> authorization) {
        return new McpOAuthMetadataGateway.OAuthServerMetadata(
                required(authorization.get("oauth_resource"), OAuthProfile.GENERIC),
                required(authorization.get("oauth_authorization_server"), OAuthProfile.GENERIC),
                required(authorization.get("oauth_authorization_endpoint"), OAuthProfile.GENERIC),
                required(authorization.get("oauth_token_endpoint"), OAuthProfile.GENERIC),
                scopes(authorization.get("oauth_scopes_supported")),
                scopes(authorization.get("oauth_token_auth_methods")));
    }

    private static Instant instant(String value, OAuthProfile profile) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (RuntimeException error) {
            throw reauthorizationRequired(profile);
        }
    }

    private static void requireAccessToken(
            Map<String, String> authorization, OAuthProfile profile) {
        required(authorization.get("access_token"), profile);
    }

    private static String required(String value, OAuthProfile profile) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 4_096
                || normalized.contains("\r") || normalized.contains("\n")) {
            throw reauthorizationRequired(profile);
        }
        return normalized;
    }

    private static void put(Map<String, String> target, String key, Instant value) {
        if (value != null) target.put(key, value.toString());
        else target.remove(key);
    }

    private static BusinessException reauthorizationRequired(OAuthProfile profile) {
        if (profile == OAuthProfile.GENERIC) {
            return new BusinessException(
                    "MCP authorization must be renewed", HttpStatus.CONFLICT,
                    "MCP_OAUTH_REAUTH_REQUIRED");
        }
        return new BusinessException(
                "GitHub MCP authorization must be renewed", HttpStatus.CONFLICT,
                "GITHUB_MCP_REAUTH_REQUIRED");
    }

    private enum OAuthProfile {
        NONE,
        GITHUB,
        GENERIC
    }

    private record Grant(
            String accessToken,
            String refreshToken,
            String tokenType,
            Set<String> scopes,
            Instant issuedAt,
            Instant expiresAt,
            Instant refreshTokenExpiresAt) {
    }

    public record AuthorizedConnection(
            McpConnection connection, Map<String, String> authorization) {
    }
}
