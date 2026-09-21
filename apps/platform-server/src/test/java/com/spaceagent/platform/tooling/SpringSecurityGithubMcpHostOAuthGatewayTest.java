package com.spaceagent.platform.tooling;

import com.spaceagent.platform.tooling.infrastructure.McpToolingProperties;
import com.spaceagent.platform.tooling.infrastructure.SpringSecurityGithubMcpHostOAuthGateway;
import com.spaceagent.platform.tooling.domain.GithubMcpOAuthMetadataGateway;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SpringSecurityGithubMcpHostOAuthGatewayTest {
    @Test
    void springSecurityBuildsPkceAndExchangesAuthorizationCode() {
        McpToolingProperties properties = new McpToolingProperties();
        properties.getGithub().setClientId("github-client-id");
        properties.getGithub().setClientSecret("github-client-secret");
        properties.getGithub().setScopes(List.of("repo", "read:user"));
        properties.getGithub().setAllowedRedirectUris(
                List.of("https://app.example/mcp/github/callback",
                        "http://127.0.0.1:49152/mcp/github/callback"));

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SpringSecurityGithubMcpHostOAuthGateway gateway =
                new SpringSecurityGithubMcpHostOAuthGateway(properties, builder
                        .messageConverters(converters -> {
                            converters.clear();
                            converters.add(new FormHttpMessageConverter());
                            converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                        })
                        .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                        .build());
        var metadata = new GithubMcpOAuthMetadataGateway.OAuthServerMetadata(
                "https://api.githubcopilot.com/mcp",
                "https://github.example/oauth",
                "https://github.example/oauth/authorize",
                "https://github.example/oauth/token",
                java.util.Set.of("repo", "read:user"));

        var begin = gateway.begin(
                "state-value", "https://app.example/mcp/github/callback", metadata);
        assertThat(begin.authorizationUrl())
                .contains("client_id=github-client-id")
                .contains("state=state-value")
                .contains("resource=")
                .contains("code_challenge=")
                .contains("code_challenge_method=S256")
                .doesNotContain("github-client-secret", begin.codeVerifier());
        assertThat(begin.codeVerifier()).hasSizeGreaterThanOrEqualTo(43);
        assertThat(gateway.begin(
                "cli-state", "http://127.0.0.1:49152/mcp/github/callback", metadata)
                .authorizationUrl())
                .contains("redirect_uri=http://127.0.0.1:49152/mcp/github/callback");

        server.expect(once(), requestTo("https://github.example/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(allOf(
                        containsString("code=authorization-code"),
                        containsString("code_verifier="),
                        containsString("resource="),
                        containsString("client_id=github-client-id"),
                        containsString("client_secret=github-client-secret"))))
                .andRespond(withSuccess("""
                        {"access_token":"access-token","token_type":"bearer",
                         "scope":"repo read:user","expires_in":3600,
                         "refresh_token":"refresh-token"}
                        """, MediaType.APPLICATION_JSON));

        var token = gateway.exchange(
                "state-value", "authorization-code", begin.redirectUri(),
                begin.codeVerifier(), metadata);
        assertThat(token.accessToken()).isEqualTo("access-token");
        assertThat(token.refreshToken()).isEqualTo("refresh-token");
        assertThat(token.scopes()).containsExactlyInAnyOrder("repo", "read:user");
        assertThat(token.expiresAt()).isAfter(token.issuedAt());
        server.verify();
    }
}
