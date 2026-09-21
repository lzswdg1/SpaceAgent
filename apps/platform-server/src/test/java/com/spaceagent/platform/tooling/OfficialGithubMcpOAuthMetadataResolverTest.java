package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.domain.GithubMcpProfiles;
import com.spaceagent.platform.tooling.domain.McpAuthType;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpConnectionState;
import com.spaceagent.platform.tooling.infrastructure.OfficialGithubMcpOAuthMetadataResolver;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OfficialGithubMcpOAuthMetadataResolverTest {
    @Test
    void followsProtectedResourceAndAuthorizationServerMetadata() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(GithubMcpProfiles.OFFICIAL_REMOTE_ENDPOINT))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).header(
                        HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer error=\"invalid_request\", resource_metadata=\""
                                + "https://api.githubcopilot.com/.well-known/"
                                + "oauth-protected-resource/mcp/\""));
        server.expect(once(), requestTo(
                        "https://api.githubcopilot.com/.well-known/"
                                + "oauth-protected-resource/mcp/"))
                .andRespond(withSuccess("""
                        {"resource":"https://api.githubcopilot.com/mcp",
                         "authorization_servers":["https://github.com/login/oauth"],
                         "scopes_supported":["repo","read:user","read:org"]}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(
                        "https://github.com/.well-known/oauth-authorization-server/login/oauth"))
                .andRespond(withSuccess("""
                        {"issuer":"https://github.com/login/oauth",
                         "authorization_endpoint":"https://github.com/login/oauth/authorize",
                         "token_endpoint":"https://github.com/login/oauth/access_token",
                         "code_challenge_methods_supported":["S256"]}
                        """, MediaType.APPLICATION_JSON));
        var resolver = new OfficialGithubMcpOAuthMetadataResolver(
                URI::create, builder.build(), new ObjectMapper());
        Instant now = Instant.parse("2026-08-24T06:00:00Z");
        McpConnection connection = new McpConnection(
                "connection", "installation", "tenant", "user",
                GithubMcpProfiles.OFFICIAL_REMOTE_ENDPOINT, "cipher", McpAuthType.OAUTH2,
                McpConnectionState.PENDING_AUTH, null, null, 1, now, now, null);

        var metadata = resolver.resolve(connection);

        assertThat(metadata.resource()).isEqualTo("https://api.githubcopilot.com/mcp");
        assertThat(metadata.authorizationServer()).isEqualTo("https://github.com/login/oauth");
        assertThat(metadata.authorizationEndpoint())
                .isEqualTo("https://github.com/login/oauth/authorize");
        assertThat(metadata.tokenEndpoint())
                .isEqualTo("https://github.com/login/oauth/access_token");
        assertThat(metadata.scopesSupported()).containsExactlyInAnyOrder(
                "repo", "read:user", "read:org");
        server.verify();
    }

    @Test
    void rejectsAuthorizationServerOutsideGithub() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(GithubMcpProfiles.OFFICIAL_REMOTE_ENDPOINT))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).header(
                        HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer resource_metadata=\"https://api.githubcopilot.com/"
                                + ".well-known/oauth-protected-resource/mcp/\""));
        server.expect(requestTo("https://api.githubcopilot.com/"
                        + ".well-known/oauth-protected-resource/mcp/"))
                .andRespond(withSuccess("""
                        {"resource":"https://api.githubcopilot.com/mcp",
                         "authorization_servers":["https://evil.example/oauth"],
                         "scopes_supported":["repo"]}
                        """, MediaType.APPLICATION_JSON));
        var resolver = new OfficialGithubMcpOAuthMetadataResolver(
                URI::create, builder.build(), new ObjectMapper());
        Instant now = Instant.parse("2026-08-24T06:00:00Z");
        McpConnection connection = new McpConnection(
                "connection", "installation", "tenant", "user",
                GithubMcpProfiles.OFFICIAL_REMOTE_ENDPOINT, "cipher", McpAuthType.OAUTH2,
                McpConnectionState.PENDING_AUTH, null, null, 1, now, now, null);

        assertThatThrownBy(() -> resolver.resolve(connection))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authorization server host");
        server.verify();
    }
}
