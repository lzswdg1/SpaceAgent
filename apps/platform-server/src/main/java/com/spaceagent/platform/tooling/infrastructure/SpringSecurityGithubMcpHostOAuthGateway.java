package com.spaceagent.platform.tooling.infrastructure;

import com.spaceagent.platform.tooling.domain.GithubMcpHostOAuthGateway;
import com.spaceagent.platform.tooling.domain.GithubMcpOAuthMetadataGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Component
public class SpringSecurityGithubMcpHostOAuthGateway implements GithubMcpHostOAuthGateway {
    private final McpToolingProperties.Github properties;
    private final RestClient restClient;

    @Autowired
    public SpringSecurityGithubMcpHostOAuthGateway(
            McpToolingProperties properties, RestClient.Builder restClientBuilder) {
        this(properties, bounded(restClientBuilder));
    }

    public SpringSecurityGithubMcpHostOAuthGateway(
            McpToolingProperties properties, RestClient restClient) {
        this.properties = properties.getGithub();
        this.restClient = restClient;
    }

    private static RestClient bounded(RestClient.Builder restClientBuilder) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        return restClientBuilder.clone()
                .requestFactory(requestFactory)
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new FormHttpMessageConverter());
                    converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
    }

    @Override
    public AuthorizationSession begin(
            String state,
            String redirectUri,
            GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata) {
        configured();
        String redirect = redirect(redirectUri);
        String safeState = bounded(state, "state", 512);
        OAuth2AuthorizationRequest.Builder builder = OAuth2AuthorizationRequest
                .authorizationCode()
                .authorizationUri(metadata.authorizationEndpoint())
                .clientId(properties.getClientId().trim())
                .redirectUri(redirect)
                .scopes(scopes(metadata))
                .state(safeState)
                .additionalParameters(Map.of("resource", metadata.resource()));
        OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
        OAuth2AuthorizationRequest request = builder.build();
        String verifier = request.getAttribute(PkceParameterNames.CODE_VERIFIER);
        if (verifier == null || verifier.isBlank()) {
            throw new IllegalStateException("OAuth PKCE verifier was not generated");
        }
        return new AuthorizationSession(
                https(request.getAuthorizationRequestUri(), "authorization-request"),
                redirect, verifier, request.getScopes());
    }

    @Override
    public TokenGrant exchange(
            String state,
            String code,
            String redirectUri,
            String codeVerifier,
            GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata) {
        configured();
        String redirect = redirect(redirectUri);
        String safeState = bounded(state, "state", 512);
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(metadata.authorizationEndpoint())
                .clientId(properties.getClientId().trim())
                .redirectUri(redirect)
                .scopes(scopes(metadata))
                .state(safeState)
                .additionalParameters(Map.of("resource", metadata.resource()))
                .attributes(values -> values.put(
                        PkceParameterNames.CODE_VERIFIER,
                        bounded(codeVerifier, "codeVerifier", 256)))
                .build();
        OAuth2AuthorizationResponse response = OAuth2AuthorizationResponse.success(
                        bounded(code, "code", 2048))
                .redirectUri(redirect)
                .state(safeState)
                .build();
        RestClientAuthorizationCodeTokenResponseClient client =
                new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(restClient);
        client.setParametersCustomizer(parameters ->
                parameters.set("resource", metadata.resource()));
        OAuth2AccessTokenResponse token = client.getTokenResponse(
                new OAuth2AuthorizationCodeGrantRequest(
                        registration(redirect, metadata),
                        new OAuth2AuthorizationExchange(request, response)));
        return grant(token);
    }

    @Override
    public TokenGrant refresh(
            String refreshToken,
            Set<String> scopes,
            GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata) {
        configured();
        Instant now = Instant.now();
        OAuth2AccessToken previous = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, "refresh-placeholder", now.minusSeconds(1),
                now, scopes == null ? Set.of() : Set.copyOf(scopes));
        RestClientRefreshTokenTokenResponseClient client =
                new RestClientRefreshTokenTokenResponseClient();
        client.setRestClient(restClient);
        client.setParametersCustomizer(parameters ->
                parameters.set("resource", metadata.resource()));
        OAuth2AccessTokenResponse token = client.getTokenResponse(
                new OAuth2RefreshTokenGrantRequest(
                        registration("https://localhost.invalid/oauth/callback", metadata), previous,
                        new OAuth2RefreshToken(
                                bounded(refreshToken, "refreshToken", 4096), now.minusSeconds(1)),
                        previous.getScopes()));
        return grant(token);
    }

    private ClientRegistration registration(
            String redirectUri,
            GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata) {
        return ClientRegistration.withRegistrationId("github-mcp")
                .clientId(properties.getClientId().trim())
                .clientSecret(properties.getClientSecret().trim())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .scope(scopes(metadata))
                .authorizationUri(metadata.authorizationEndpoint())
                .tokenUri(metadata.tokenEndpoint())
                .clientName("SpaceAgent GitHub MCP")
                .build();
    }

    private TokenGrant grant(OAuth2AccessTokenResponse response) {
        if (response == null) throw new IllegalStateException("OAuth token response is missing");
        if (response.getAccessToken() == null) {
            throw new IllegalStateException("OAuth access token response is missing");
        }
        if (response.getAccessToken().getTokenValue() == null
                || response.getAccessToken().getTokenValue().isBlank()) {
            throw new IllegalStateException("OAuth access token value is missing");
        }
        OAuth2AccessToken access = response.getAccessToken();
        OAuth2RefreshToken refresh = response.getRefreshToken();
        return new TokenGrant(
                access.getTokenValue(), refresh == null ? null : refresh.getTokenValue(),
                access.getTokenType().getValue(), access.getScopes(), access.getIssuedAt(),
                access.getExpiresAt(), refresh == null ? null : refresh.getExpiresAt());
    }

    private void configured() {
        if (properties.getClientId() == null || properties.getClientId().isBlank()
                || properties.getClientId().length() > 512) {
            throw new IllegalStateException("GitHub MCP OAuth client-id is not configured");
        }
        if (properties.getClientSecret() == null || properties.getClientSecret().isBlank()
                || properties.getClientSecret().length() > 4096) {
            throw new IllegalStateException("GitHub MCP OAuth client-secret is not configured");
        }
        if (properties.getAllowedRedirectUris().isEmpty()) {
            throw new IllegalStateException("GitHub MCP OAuth redirect allowlist is empty");
        }
    }

    private String redirect(String value) {
        String normalized = redirectUri(value);
        boolean allowed = properties.getAllowedRedirectUris().stream()
                .map(SpringSecurityGithubMcpHostOAuthGateway::redirectUri)
                .anyMatch(normalized::equals);
        if (!allowed) throw new IllegalArgumentException("redirectUri is not allowlisted");
        return normalized;
    }

    private static String redirectUri(String value) {
        try {
            URI uri = URI.create(bounded(value, "redirectUri", 2000)).normalize();
            boolean secureWeb = "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null;
            boolean loopback = "http".equalsIgnoreCase(uri.getScheme())
                    && ("127.0.0.1".equals(uri.getHost()) || "::1".equals(uri.getHost()))
                    && uri.getPort() > 0;
            if ((!secureWeb && !loopback) || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return uri.toString();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(
                    "redirectUri must use HTTPS or an explicit loopback HTTP port");
        }
    }

    private Set<String> scopes(
            GithubMcpOAuthMetadataGateway.OAuthServerMetadata metadata) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String scope : properties.getScopes()) {
            String value = bounded(scope, "scope", 100);
            if (!value.matches("[A-Za-z0-9:_-]+")) {
                throw new IllegalArgumentException("OAuth scope is invalid");
            }
            result.add(value);
        }
        if (result.isEmpty()) throw new IllegalStateException("GitHub MCP OAuth scopes are empty");
        if (metadata == null || metadata.scopesSupported() == null
                || !metadata.scopesSupported().containsAll(result)) {
            throw new IllegalStateException(
                    "GitHub MCP protected resource does not support configured scopes");
        }
        return Set.copyOf(result);
    }

    private static String https(String value, String field) {
        try {
            URI uri = URI.create(bounded(value, field, 2000)).normalize();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return uri.toString();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException(field + " must be an HTTPS URL");
        }
    }

    private static String bounded(String value, String field, int max) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > max
                || normalized.contains("\r") || normalized.contains("\n")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return normalized;
    }
}
