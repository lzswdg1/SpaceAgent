package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.domain.McpAuthType;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpConnectionState;
import com.spaceagent.platform.tooling.infrastructure.GenericMcpOAuthMetadataResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GenericMcpOAuthMetadataResolverTest {
    private static final String RESOURCE = "https://mcp.acme.example/mcp";
    private static final String RESOURCE_METADATA =
            "https://mcp.acme.example/.well-known/oauth-protected-resource/mcp";
    private static final String ISSUER = "https://login.acme.example/oauth";
    private static final String SERVER_METADATA =
            "https://login.acme.example/.well-known/oauth-authorization-server/oauth";

    @Test
    void followsChallengeAndSelectsOnlyConfiguredAuthorizationServer() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(RESOURCE))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).header(
                        HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer resource_metadata=\"" + RESOURCE_METADATA + "\""));
        server.expect(once(), requestTo(RESOURCE_METADATA))
                .andRespond(withSuccess(resourceMetadata(), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(SERVER_METADATA))
                .andRespond(withSuccess(serverMetadata(), MediaType.APPLICATION_JSON));

        var resolver = new GenericMcpOAuthMetadataResolver(
                URI::create, builder.build(), new ObjectMapper());
        var metadata = resolver.resolve(connection(), Set.of(ISSUER));

        assertThat(metadata.resource()).isEqualTo(RESOURCE);
        assertThat(metadata.authorizationServer()).isEqualTo(ISSUER);
        assertThat(metadata.authorizationEndpoint())
                .isEqualTo("https://login.acme.example/oauth/authorize");
        assertThat(metadata.tokenEndpoint())
                .isEqualTo("https://login.acme.example/oauth/token");
        assertThat(metadata.scopesSupported())
                .containsExactlyInAnyOrder("tools.read", "tools.call");
        assertThat(metadata.tokenEndpointAuthenticationMethods())
                .containsExactlyInAnyOrder("client_secret_post", "none");
        server.verify();
    }

    @Test
    void usesWellKnownAndOidcFallbackButRejectsMissingPkce() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(RESOURCE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo(RESOURCE_METADATA))
                .andRespond(withSuccess(resourceMetadata(), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(SERVER_METADATA))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(once(), requestTo(
                        "https://login.acme.example/oauth/.well-known/openid-configuration"))
                .andRespond(withSuccess("""
                        {"issuer":"https://login.acme.example/oauth",
                         "authorization_endpoint":"https://login.acme.example/oauth/authorize",
                         "token_endpoint":"https://login.acme.example/oauth/token",
                         "code_challenge_methods_supported":["plain"]}
                        """, MediaType.APPLICATION_JSON));
        var resolver = new GenericMcpOAuthMetadataResolver(
                URI::create, builder.build(), new ObjectMapper());

        assertThatThrownBy(() -> resolver.resolve(connection(), Set.of(ISSUER)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PKCE S256");
        server.verify();
    }

    private static String resourceMetadata() {
        return """
                {"resource":"https://mcp.acme.example/mcp",
                 "authorization_servers":["https://unconfigured.example", "%s"],
                 "scopes_supported":["tools.read","tools.call"]}
                """.formatted(ISSUER);
    }

    private static String serverMetadata() {
        return """
                {"issuer":"https://login.acme.example/oauth",
                 "authorization_endpoint":"https://login.acme.example/oauth/authorize",
                 "token_endpoint":"https://login.acme.example/oauth/token",
                 "code_challenge_methods_supported":["S256"],
                 "token_endpoint_auth_methods_supported":["client_secret_post","none"]}
                """;
    }

    private static McpConnection connection() {
        Instant now = Instant.parse("2026-09-05T00:00:00Z");
        return new McpConnection(
                "connection", "installation", "tenant", "user", RESOURCE, "cipher",
                McpAuthType.OAUTH2, McpConnectionState.PENDING_AUTH,
                null, null, 1, now, now, null);
    }
}
