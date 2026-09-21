package com.spaceagent.platform.tooling.infrastructure;

import com.spaceagent.platform.tooling.domain.McpOAuthClientAuthenticationMethod;
import com.spaceagent.platform.tooling.domain.McpOAuthClientGateway;
import com.spaceagent.platform.tooling.domain.McpOAuthClientRegistration;
import com.spaceagent.platform.tooling.domain.McpOAuthMetadataGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
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
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Component
public class SpringSecurityMcpOAuthClientGateway implements McpOAuthClientGateway {
    private final RestClient restClient;

    @Autowired
    public SpringSecurityMcpOAuthClientGateway(RestClient.Builder restClientBuilder) {
        this(bounded(restClientBuilder));
    }

    public SpringSecurityMcpOAuthClientGateway(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public AuthorizationSession begin(
            String state,
            String redirectUri,
            McpOAuthClientRegistration registration,
            McpOAuthMetadataGateway.OAuthServerMetadata metadata) {
        validate(registration, metadata);
        String redirect = redirect(redirectUri, registration);
        String safeState = bounded(state, "state", 512);
        OAuth2AuthorizationRequest.Builder builder = OAuth2AuthorizationRequest
                .authorizationCode()
                .authorizationUri(metadata.authorizationEndpoint())
                .clientId(registration.clientId())
                .redirectUri(redirect)
                .scopes(scopes(registration, metadata))
                .state(safeState)
                .additionalParameters(Map.of("resource", metadata.resource()));
        OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
        OAuth2AuthorizationRequest request = builder.build();
        String verifier = request.getAttribute(PkceParameterNames.CODE_VERIFIER);
        if (verifier == null || verifier.isBlank()) {
            throw new IllegalStateException("MCP OAuth PKCE verifier was not generated");
        }
        return new AuthorizationSession(
                https(request.getAuthorizationRequestUri(), "authorization request"),
                redirect, verifier, request.getScopes());
    }

    @Override
    public TokenGrant exchange(
            String state,
            String code,
            String redirectUri,
            String codeVerifier,
            McpOAuthClientRegistration registration,
            McpOAuthMetadataGateway.OAuthServerMetadata metadata) {
        validate(registration, metadata);
        String redirect = redirect(redirectUri, registration);
        String safeState = bounded(state, "state", 512);
        OAuth2AuthorizationRequest request = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(metadata.authorizationEndpoint())
                .clientId(registration.clientId())
                .redirectUri(redirect)
                .scopes(scopes(registration, metadata))
                .state(safeState)
                .additionalParameters(Map.of("resource", metadata.resource()))
                .attributes(values -> values.put(
                        PkceParameterNames.CODE_VERIFIER,
                        bounded(codeVerifier, "code verifier", 256)))
                .build();
        OAuth2AuthorizationResponse response = OAuth2AuthorizationResponse.success(
                        bounded(code, "authorization code", 2_048))
                .redirectUri(redirect)
                .state(safeState)
                .build();
        RestClientAuthorizationCodeTokenResponseClient client =
                new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(restClient);
        client.setParametersCustomizer(parameters ->
                parameters.set("resource", metadata.resource()));
        return grant(client.getTokenResponse(new OAuth2AuthorizationCodeGrantRequest(
                registration(redirect, registration, metadata),
                new OAuth2AuthorizationExchange(request, response))));
    }

    @Override
    public TokenGrant refresh(
            String refreshToken,
            Set<String> currentScopes,
            McpOAuthClientRegistration registration,
            McpOAuthMetadataGateway.OAuthServerMetadata metadata) {
        validate(registration, metadata);
        Instant now = Instant.now();
        Set<String> scopes = currentScopes == null || currentScopes.isEmpty()
                ? scopes(registration, metadata) : Set.copyOf(currentScopes);
        OAuth2AccessToken previous = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, "refresh-placeholder",
                now.minusSeconds(1), now, scopes);
        RestClientRefreshTokenTokenResponseClient client =
                new RestClientRefreshTokenTokenResponseClient();
        client.setRestClient(restClient);
        client.setParametersCustomizer(parameters ->
                parameters.set("resource", metadata.resource()));
        return grant(client.getTokenResponse(new OAuth2RefreshTokenGrantRequest(
                registration(registration.allowedRedirectUris().iterator().next(),
                        registration, metadata),
                previous,
                new OAuth2RefreshToken(
                        bounded(refreshToken, "refresh token", 4_096), now.minusSeconds(1)),
                scopes)));
    }

    private ClientRegistration registration(
            String redirectUri,
            McpOAuthClientRegistration registration,
            McpOAuthMetadataGateway.OAuthServerMetadata metadata) {
        ClientRegistration.Builder builder = ClientRegistration
                .withRegistrationId(registration.id())
                .clientId(registration.clientId())
                .clientAuthenticationMethod(authenticationMethod(registration.authenticationMethod()))
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .scope(scopes(registration, metadata))
                .authorizationUri(metadata.authorizationEndpoint())
                .tokenUri(metadata.tokenEndpoint())
                .clientName("SpaceAgent MCP " + registration.id());
        if (registration.clientSecret() != null) {
            builder.clientSecret(registration.clientSecret());
        }
        return builder.build();
    }

    private static ClientAuthenticationMethod authenticationMethod(
            McpOAuthClientAuthenticationMethod method) {
        return switch (method) {
            case NONE -> ClientAuthenticationMethod.NONE;
            case CLIENT_SECRET_BASIC -> ClientAuthenticationMethod.CLIENT_SECRET_BASIC;
            case CLIENT_SECRET_POST -> ClientAuthenticationMethod.CLIENT_SECRET_POST;
        };
    }

    private static void validate(
            McpOAuthClientRegistration registration,
            McpOAuthMetadataGateway.OAuthServerMetadata metadata) {
        if (registration == null || metadata == null
                || !registration.authorizationServer().equals(metadata.authorizationServer())) {
            throw new IllegalStateException("MCP OAuth client registration does not match issuer");
        }
        String method = switch (registration.authenticationMethod()) {
            case NONE -> "none";
            case CLIENT_SECRET_BASIC -> "client_secret_basic";
            case CLIENT_SECRET_POST -> "client_secret_post";
        };
        if (!metadata.tokenEndpointAuthenticationMethods().isEmpty()
                && !metadata.tokenEndpointAuthenticationMethods().contains(method)) {
            throw new IllegalStateException(
                    "MCP OAuth token endpoint does not support configured client authentication");
        }
    }

    private static Set<String> scopes(
            McpOAuthClientRegistration registration,
            McpOAuthMetadataGateway.OAuthServerMetadata metadata) {
        LinkedHashSet<String> scopes = new LinkedHashSet<>(registration.scopes());
        if (!metadata.scopesSupported().isEmpty()
                && !metadata.scopesSupported().containsAll(scopes)) {
            throw new IllegalStateException("MCP OAuth configured scopes are not supported");
        }
        return Set.copyOf(scopes);
    }

    private static String redirect(
            String value, McpOAuthClientRegistration registration) {
        String normalized = ConfiguredMcpOAuthClientRegistrationProvider.redirectUri(value);
        if (!registration.allowedRedirectUris().contains(normalized)) {
            throw new IllegalArgumentException("MCP OAuth redirect URI is not allowlisted");
        }
        return normalized;
    }

    private static TokenGrant grant(OAuth2AccessTokenResponse response) {
        if (response == null || response.getAccessToken() == null
                || response.getAccessToken().getTokenValue() == null
                || response.getAccessToken().getTokenValue().isBlank()) {
            throw new IllegalStateException("MCP OAuth access token response is missing");
        }
        OAuth2AccessToken access = response.getAccessToken();
        OAuth2RefreshToken refresh = response.getRefreshToken();
        return new TokenGrant(
                access.getTokenValue(), refresh == null ? null : refresh.getTokenValue(),
                access.getTokenType().getValue(), access.getScopes(), access.getIssuedAt(),
                access.getExpiresAt(), refresh == null ? null : refresh.getExpiresAt());
    }

    private static String https(String value, String field) {
        try {
            URI uri = URI.create(bounded(value, field, 4_096)).normalize();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            return uri.toString();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("MCP OAuth " + field + " must be HTTPS");
        }
    }

    private static String bounded(String value, String field, int maximum) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty() || result.length() > maximum
                || result.contains("\r") || result.contains("\n")) {
            throw new IllegalArgumentException("MCP OAuth " + field + " is invalid");
        }
        return result;
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
}
