package com.spaceagent.platform.tooling.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.api.TenantMembershipView;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.tooling.api.McpOAuthApplicationApi;
import com.spaceagent.platform.tooling.domain.GithubMcpProfiles;
import com.spaceagent.platform.tooling.domain.McpAuthType;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpConnectionSecretCipher;
import com.spaceagent.platform.tooling.domain.McpConnectionState;
import com.spaceagent.platform.tooling.domain.McpInstallation;
import com.spaceagent.platform.tooling.domain.McpInstallationScope;
import com.spaceagent.platform.tooling.domain.McpInstallationState;
import com.spaceagent.platform.tooling.domain.McpMarketplaceRepository;
import com.spaceagent.platform.tooling.domain.McpOAuthClientGateway;
import com.spaceagent.platform.tooling.domain.McpOAuthClientRegistration;
import com.spaceagent.platform.tooling.domain.McpOAuthClientRegistrationProvider;
import com.spaceagent.platform.tooling.domain.McpOAuthMetadataGateway;
import com.spaceagent.platform.tooling.domain.McpOAuthState;
import com.spaceagent.platform.tooling.domain.McpOAuthStateRepository;
import com.spaceagent.platform.tooling.domain.McpServerVersionState;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Service
public class McpOAuthApplicationService implements McpOAuthApplicationApi {
    public static final String PROFILE = "generic-oauth-v1";
    private static final int STATE_SECONDS = 600;

    private final McpMarketplaceRepository marketplace;
    private final McpOAuthStateRepository states;
    private final McpConnectionSecretCipher cipher;
    private final McpOAuthMetadataGateway metadataGateway;
    private final McpOAuthClientGateway oauth;
    private final McpOAuthClientRegistrationProvider registrations;
    private final IdentityApplicationApi identity;
    private final ObjectMapper json;
    private final IdGenerator ids;
    private final TimeProvider time;
    private final SecureRandom random = new SecureRandom();

    public McpOAuthApplicationService(
            McpMarketplaceRepository marketplace,
            McpOAuthStateRepository states,
            McpConnectionSecretCipher cipher,
            McpOAuthMetadataGateway metadataGateway,
            McpOAuthClientGateway oauth,
            McpOAuthClientRegistrationProvider registrations,
            IdentityApplicationApi identity,
            ObjectMapper json,
            IdGenerator ids,
            TimeProvider time) {
        this.marketplace = marketplace;
        this.states = states;
        this.cipher = cipher;
        this.metadataGateway = metadataGateway;
        this.oauth = oauth;
        this.registrations = registrations;
        this.identity = identity;
        this.json = json;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public AuthorizationView begin(BeginCommand command) {
        McpConnection connection = requireConnection(
                command.tenantId(), command.userId(), command.connectionId(), true);
        requireGenericOAuth(connection);
        if (registrations.authorizationServers().isEmpty()) throw clientNotConfigured();

        McpOAuthMetadataGateway.OAuthServerMetadata metadata;
        try {
            metadata = metadataGateway.resolve(
                    connection, registrations.authorizationServers());
        } catch (RuntimeException error) {
            throw metadataUnavailable();
        }
        McpOAuthClientRegistration registration = registrations
                .findByAuthorizationServer(metadata.authorizationServer())
                .orElseThrow(McpOAuthApplicationService::clientNotConfigured);
        String rawState = token();
        McpOAuthClientGateway.AuthorizationSession session;
        try {
            session = oauth.begin(rawState, command.redirectUri(), registration, metadata);
        } catch (IllegalArgumentException error) {
            throw new BusinessException(
                    "MCP OAuth redirect URI is invalid", HttpStatus.BAD_REQUEST,
                    "MCP_OAUTH_REDIRECT_INVALID");
        } catch (RuntimeException error) {
            throw oauthUnavailable();
        }

        Instant now = time.now();
        Instant expiresAt = now.plusSeconds(STATE_SECONDS);
        OAuthTransaction transaction = new OAuthTransaction(
                PROFILE, connection.revision(), registration.id(), session.redirectUri(),
                session.codeVerifier(), metadata.resource(), metadata.authorizationServer(),
                metadata.authorizationEndpoint(), metadata.tokenEndpoint(),
                metadata.scopesSupported(), metadata.tokenEndpointAuthenticationMethods(),
                session.scopes());
        states.save(new McpOAuthState(
                ids.nextId(), connection.id(), connection.revision(),
                command.tenantId(), command.userId(), hash(rawState),
                cipher.encrypt(write(transaction)), expiresAt, now, null));
        return new AuthorizationView(
                rawState, session.authorizationUrl(), metadata.authorizationServer(),
                registration.id(), expiresAt);
    }

    @Override
    public GrantView complete(CompleteCommand command) {
        String rawState = required(command.state(), "state", 512);
        String code = optional(command.code(), 2_048);
        String providerError = optional(command.error(), 100);
        if ((code == null) == (providerError == null)) {
            throw new BusinessException(
                    "MCP OAuth callback must contain exactly one result", HttpStatus.BAD_REQUEST,
                    "MCP_OAUTH_REQUEST_INVALID");
        }
        Instant now = time.now();
        McpOAuthState state = states.consume(
                        hash(rawState), command.tenantId(), command.userId(), now)
                .orElseThrow(McpOAuthApplicationService::invalidState);
        try {
            if (providerError != null) {
                throw new BusinessException(
                        "MCP OAuth authorization was denied", HttpStatus.BAD_REQUEST,
                        "MCP_OAUTH_DENIED");
            }
            McpConnection connection = requireConnection(
                    command.tenantId(), command.userId(), state.connectionId(), true);
            requireGenericOAuth(connection);
            OAuthTransaction transaction = readTransaction(
                    cipher.decrypt(state.encryptedProviderSession()));
            if (!PROFILE.equals(transaction.profile())
                    || transaction.connectionRevision() != state.connectionRevision()
                    || connection.revision() != state.connectionRevision()) {
                throw connectionChanged();
            }
            McpOAuthClientRegistration registration = registrations
                    .findById(transaction.clientRegistrationId())
                    .filter(value -> value.authorizationServer()
                            .equals(transaction.authorizationServer()))
                    .orElseThrow(McpOAuthApplicationService::clientNotConfigured);
            McpOAuthMetadataGateway.OAuthServerMetadata metadata = metadata(transaction);
            McpOAuthClientGateway.TokenGrant grant;
            try {
                grant = oauth.exchange(
                        rawState, code, transaction.redirectUri(), transaction.codeVerifier(),
                        registration, metadata);
            } catch (RuntimeException error) {
                throw new BusinessException(
                        "MCP OAuth code exchange failed", HttpStatus.BAD_GATEWAY,
                        "MCP_OAUTH_EXCHANGE_FAILED");
            }
            Map<String, String> authorization = tokenAuthorization(
                    grant, registration.id(), metadata, transaction.requestedScopes());
            McpConnection pendingValidation = new McpConnection(
                    connection.id(), connection.installationId(), connection.tenantId(),
                    command.userId(), connection.endpointUrl(),
                    cipher.encrypt(write(authorization)), connection.authType(),
                    McpConnectionState.PENDING_VALIDATION, null, null,
                    connection.revision() + 1, connection.createdAt(), now, null);
            if (!marketplace.saveConnectionIfRevision(
                    pendingValidation, connection.revision())) {
                throw connectionChanged();
            }
            return new GrantView(
                    pendingValidation.id(), pendingValidation.state(), grant.expiresAt(),
                    grantedScopes(grant, transaction.requestedScopes()));
        } finally {
            states.redact(state.id(), time.now());
        }
    }

    private McpConnection requireConnection(
            String tenantId, String userId, String connectionId, boolean write) {
        TenantMembershipView membership = identity.findTenantMembership(tenantId, userId)
                .filter(value -> value.status() == TenantMembershipStatus.ACTIVE)
                .orElseThrow(() -> denied("Organization membership required"));
        McpConnection connection = marketplace.findConnection(connectionId)
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.state() != McpConnectionState.REVOKED)
                .orElseThrow(McpOAuthApplicationService::notFound);
        McpInstallation installation = marketplace.findInstallation(connection.installationId())
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.state() == McpInstallationState.INSTALLED)
                .orElseThrow(McpOAuthApplicationService::notFound);
        if (installation.scope() == McpInstallationScope.USER
                && !installation.subjectId().equals(userId)) {
            throw notFound();
        }
        if (write && installation.scope() == McpInstallationScope.ORGANIZATION
                && membership.role() != TenantRole.OWNER
                && membership.role() != TenantRole.ADMIN) {
            throw denied("Organization manager required");
        }
        marketplace.findVersion(installation.serverVersionId())
                .filter(value -> value.entryId().equals(installation.entryId()))
                .filter(value -> value.lifecycleState() != McpServerVersionState.REVOKED)
                .orElseThrow(McpOAuthApplicationService::notFound);
        return connection;
    }

    private static void requireGenericOAuth(McpConnection connection) {
        if (connection.authType() != McpAuthType.OAUTH2) {
            throw new BusinessException(
                    "MCP Connection does not use OAuth", HttpStatus.CONFLICT,
                    "MCP_OAUTH_STATE_CONFLICT");
        }
        if (GithubMcpProfiles.isOfficialRemote(connection)) {
            throw new BusinessException(
                    "Official GitHub MCP uses the GitHub OAuth endpoint", HttpStatus.CONFLICT,
                    "MCP_OAUTH_PROFILE_CONFLICT");
        }
    }

    private Map<String, String> tokenAuthorization(
            McpOAuthClientGateway.TokenGrant grant,
            String registrationId,
            McpOAuthMetadataGateway.OAuthServerMetadata metadata,
            Set<String> requestedScopes) {
        String tokenType = grant.tokenType() == null ? "Bearer" : grant.tokenType().trim();
        if (!"Bearer".equalsIgnoreCase(tokenType)) {
            throw new BusinessException(
                    "MCP OAuth token type is unsupported", HttpStatus.BAD_GATEWAY,
                    "MCP_OAUTH_TOKEN_INVALID");
        }
        Map<String, String> value = new LinkedHashMap<>();
        value.put("oauth_profile", PROFILE);
        value.put("oauth_client_registration_id", registrationId);
        value.put("access_token", remoteToken(grant.accessToken()));
        value.put("token_type", "Bearer");
        value.put("oauth_resource", metadata.resource());
        value.put("oauth_authorization_server", metadata.authorizationServer());
        value.put("oauth_authorization_endpoint", metadata.authorizationEndpoint());
        value.put("oauth_token_endpoint", metadata.tokenEndpoint());
        value.put("oauth_scopes_supported", String.join(" ", metadata.scopesSupported()));
        value.put("oauth_token_auth_methods",
                String.join(" ", metadata.tokenEndpointAuthenticationMethods()));
        if (grant.refreshToken() != null && !grant.refreshToken().isBlank()) {
            value.put("refresh_token", remoteToken(grant.refreshToken()));
        }
        Set<String> scopes = grantedScopes(grant, requestedScopes);
        if (!scopes.isEmpty()) value.put("scope", String.join(" ", scopes));
        put(value, "issued_at", grant.issuedAt());
        put(value, "expires_at", grant.expiresAt());
        put(value, "refresh_token_expires_at", grant.refreshTokenExpiresAt());
        return value;
    }

    private static Set<String> grantedScopes(
            McpOAuthClientGateway.TokenGrant grant, Set<String> requestedScopes) {
        Set<String> source = grant.scopes() == null || grant.scopes().isEmpty()
                ? requestedScopes : grant.scopes();
        if (source.size() > 100) throw invalidRemoteToken();
        Set<String> result = new LinkedHashSet<>();
        for (String scope : source) {
            if (scope == null || !scope.matches("[A-Za-z0-9:._/-]{1,100}")) {
                throw invalidRemoteToken();
            }
            result.add(scope);
        }
        return Set.copyOf(result);
    }

    private McpOAuthMetadataGateway.OAuthServerMetadata metadata(OAuthTransaction value) {
        return new McpOAuthMetadataGateway.OAuthServerMetadata(
                value.resource(), value.authorizationServer(), value.authorizationEndpoint(),
                value.tokenEndpoint(), value.scopesSupported(),
                value.tokenEndpointAuthenticationMethods());
    }

    private String write(Object value) {
        try {
            String encoded = json.writeValueAsString(value);
            if (encoded.length() > 32_000) {
                throw new IllegalStateException("MCP OAuth payload exceeds limit");
            }
            return encoded;
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Unable to encode MCP OAuth state");
        }
    }

    private OAuthTransaction readTransaction(String value) {
        try {
            return json.readValue(value, OAuthTransaction.class);
        } catch (Exception error) {
            throw invalidState();
        }
    }

    private String token() {
        byte[] value = new byte[48];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash MCP OAuth state");
        }
    }

    private static String required(String value, String field, int maximum) {
        String result = optional(value, maximum);
        if (result == null) {
            throw new BusinessException(
                    "MCP OAuth " + field + " is invalid", HttpStatus.BAD_REQUEST,
                    "MCP_OAUTH_REQUEST_INVALID");
        }
        return result;
    }

    private static String optional(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim();
        if (result.length() > maximum || result.contains("\r") || result.contains("\n")) {
            throw new BusinessException(
                    "MCP OAuth callback is invalid", HttpStatus.BAD_REQUEST,
                    "MCP_OAUTH_REQUEST_INVALID");
        }
        return result;
    }

    private static String remoteToken(String value) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty() || result.length() > 4_096
                || result.contains("\r") || result.contains("\n")) {
            throw invalidRemoteToken();
        }
        return result;
    }

    private static BusinessException invalidRemoteToken() {
        return new BusinessException(
                "MCP OAuth token response is invalid", HttpStatus.BAD_GATEWAY,
                "MCP_OAUTH_TOKEN_INVALID");
    }

    private static void put(Map<String, String> target, String key, Instant value) {
        if (value != null) target.put(key, value.toString());
    }

    private static BusinessException invalidState() {
        return new BusinessException(
                "MCP OAuth state is invalid, expired, or consumed", HttpStatus.CONFLICT,
                "MCP_OAUTH_STATE_INVALID");
    }

    private static BusinessException connectionChanged() {
        return new BusinessException(
                "MCP Connection changed during authorization", HttpStatus.CONFLICT,
                "MCP_OAUTH_CONNECTION_CHANGED");
    }

    private static BusinessException clientNotConfigured() {
        return new BusinessException(
                "MCP OAuth client is not configured for this authorization server",
                HttpStatus.SERVICE_UNAVAILABLE, "MCP_OAUTH_CLIENT_NOT_CONFIGURED");
    }

    private static BusinessException metadataUnavailable() {
        return new BusinessException(
                "MCP OAuth metadata discovery failed", HttpStatus.BAD_GATEWAY,
                "MCP_OAUTH_METADATA_UNAVAILABLE");
    }

    private static BusinessException oauthUnavailable() {
        return new BusinessException(
                "MCP OAuth authorization could not start", HttpStatus.BAD_GATEWAY,
                "MCP_OAUTH_UNAVAILABLE");
    }

    private static BusinessException denied(String message) {
        return new BusinessException(message, HttpStatus.FORBIDDEN, "MCP_ACCESS_DENIED");
    }

    private static BusinessException notFound() {
        return new BusinessException(
                "MCP Connection not found", HttpStatus.NOT_FOUND, "MCP_NOT_FOUND");
    }

    private record OAuthTransaction(
            String profile,
            long connectionRevision,
            String clientRegistrationId,
            String redirectUri,
            String codeVerifier,
            String resource,
            String authorizationServer,
            String authorizationEndpoint,
            String tokenEndpoint,
            Set<String> scopesSupported,
            Set<String> tokenEndpointAuthenticationMethods,
            Set<String> requestedScopes) {
        private OAuthTransaction {
            scopesSupported = scopesSupported == null ? Set.of() : Set.copyOf(scopesSupported);
            tokenEndpointAuthenticationMethods = tokenEndpointAuthenticationMethods == null
                    ? Set.of() : Set.copyOf(tokenEndpointAuthenticationMethods);
            requestedScopes = requestedScopes == null ? Set.of() : Set.copyOf(requestedScopes);
        }
    }
}
