package com.spaceagent.platform.tooling;

import com.spaceagent.platform.tooling.domain.McpOAuthClientAuthenticationMethod;
import com.spaceagent.platform.tooling.domain.McpOAuthClientRegistration;
import com.spaceagent.platform.tooling.domain.McpOAuthMetadataGateway;
import com.spaceagent.platform.tooling.infrastructure.SpringSecurityMcpOAuthClientGateway;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SpringSecurityMcpOAuthClientGatewayTest {
    @Test
    void buildsPkceAndSendsResourceForExchangeAndRefresh() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        var gateway = new SpringSecurityMcpOAuthClientGateway(builder
                .messageConverters(converters -> {
                    converters.clear();
                    converters.add(new FormHttpMessageConverter());
                    converters.add(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build());
        var registration = new McpOAuthClientRegistration(
                "acme", "https://login.acme.example/oauth", "spaceagent-client",
                "client-secret", McpOAuthClientAuthenticationMethod.CLIENT_SECRET_POST,
                Set.of("tools.read", "tools.call"),
                Set.of("https://app.example/mcp/oauth/callback"));
        var metadata = new McpOAuthMetadataGateway.OAuthServerMetadata(
                "https://mcp.acme.example/mcp", "https://login.acme.example/oauth",
                "https://login.acme.example/oauth/authorize",
                "https://login.acme.example/oauth/token",
                Set.of("tools.read", "tools.call"), Set.of("client_secret_post"));

        var begin = gateway.begin(
                "state-value", "https://app.example/mcp/oauth/callback",
                registration, metadata);
        assertThat(begin.authorizationUrl())
                .contains("client_id=spaceagent-client")
                .contains("state=state-value")
                .contains("resource=")
                .contains("code_challenge=")
                .contains("code_challenge_method=S256")
                .doesNotContain("client-secret", begin.codeVerifier());

        server.expect(once(), requestTo("https://login.acme.example/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(allOf(
                        containsString("grant_type=authorization_code"),
                        containsString("code=authorization-code"),
                        containsString("code_verifier="),
                        containsString("resource="),
                        containsString("client_id=spaceagent-client"),
                        containsString("client_secret=client-secret"))))
                .andRespond(withSuccess("""
                        {"access_token":"access-token","token_type":"bearer",
                         "scope":"tools.read tools.call","expires_in":3600,
                         "refresh_token":"refresh-token"}
                        """, MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo("https://login.acme.example/oauth/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(allOf(
                        containsString("grant_type=refresh_token"),
                        containsString("refresh_token=refresh-token"),
                        containsString("resource="),
                        containsString("client_secret=client-secret"))))
                .andRespond(withSuccess("""
                        {"access_token":"refreshed-access","token_type":"bearer",
                         "scope":"tools.read tools.call","expires_in":3600}
                        """, MediaType.APPLICATION_JSON));
        var grant = gateway.exchange(
                "state-value", "authorization-code", begin.redirectUri(),
                begin.codeVerifier(), registration, metadata);
        assertThat(grant.accessToken()).isEqualTo("access-token");
        assertThat(grant.refreshToken()).isEqualTo("refresh-token");
        assertThat(grant.expiresAt()).isAfter(grant.issuedAt());

        var refreshed = gateway.refresh(
                "refresh-token", grant.scopes(), registration, metadata);
        assertThat(refreshed.accessToken()).isEqualTo("refreshed-access");
        assertThat(refreshed.issuedAt()).isBeforeOrEqualTo(Instant.now());
        server.verify();
    }
}
