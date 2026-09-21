package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.domain.McpOAuthClientGateway;
import com.spaceagent.platform.tooling.domain.McpOAuthMetadataGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "platform.tooling.mcp.oauth.clients[0].id=acme",
        "platform.tooling.mcp.oauth.clients[0].authorization-server=https://login.acme.example/oauth",
        "platform.tooling.mcp.oauth.clients[0].client-id=spaceagent-client",
        "platform.tooling.mcp.oauth.clients[0].client-secret=client-secret",
        "platform.tooling.mcp.oauth.clients[0].authentication-method=client_secret_post",
        "platform.tooling.mcp.oauth.clients[0].scopes[0]=tools.read",
        "platform.tooling.mcp.oauth.clients[0].allowed-redirect-uris[0]="
                + "https://app.example/mcp/oauth/callback"
})
@AutoConfigureMockMvc
class PlatformMcpOAuthHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean McpOAuthMetadataGateway metadata;
    @MockitoBean McpOAuthClientGateway oauth;

    @Test
    void genericOAuthEndsPendingValidationWithoutReturningGrantSecrets() throws Exception {
        when(metadata.resolve(any(), any())).thenReturn(new McpOAuthMetadataGateway.OAuthServerMetadata(
                "https://mcp.acme.example/mcp", "https://login.acme.example/oauth",
                "https://login.acme.example/oauth/authorize",
                "https://login.acme.example/oauth/token",
                Set.of("tools.read"), Set.of("client_secret_post")));
        when(oauth.begin(any(), any(), any(), any())).thenReturn(
                new McpOAuthClientGateway.AuthorizationSession(
                        "https://login.acme.example/oauth/authorize?request=bounded",
                        "https://app.example/mcp/oauth/callback", "v".repeat(64),
                        Set.of("tools.read")));
        when(oauth.exchange(any(), any(), any(), any(), any(), any())).thenReturn(
                new McpOAuthClientGateway.TokenGrant(
                        "http-access-secret", "http-refresh-secret", "Bearer",
                        Set.of("tools.read"), Instant.now(),
                        Instant.now().plusSeconds(3_600), Instant.now().plusSeconds(7_200)));

        JsonNode authentication = data(mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "username", "mcp-oauth-http@example.com",
                                "password", "password123",
                                "displayName", "MCP OAuth"))))
                .andExpect(status().isOk()).andReturn());
        String token = authentication.path("token").asText();
        JsonNode catalog = data(mvc.perform(get("/api/v1/mcp-marketplace/catalog")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn());
        JsonNode custom = find(catalog, "slug", "custom-streamable-http");
        JsonNode installation = data(mvc.perform(post("/api/v1/mcp-marketplace/installations")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "entryId", custom.path("id").asText(), "scope", "USER"))))
                .andExpect(status().isCreated()).andReturn());
        JsonNode connection = data(mvc.perform(post("/api/v1/mcp-marketplace/connections")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "installationId", installation.path("id").asText(),
                                "endpointUrl", "https://mcp.acme.example/mcp",
                                "authType", "OAUTH2", "auth", Map.of()))))
                .andExpect(status().isCreated()).andReturn());
        assertThat(connection.path("state").asText()).isEqualTo("PENDING_AUTH");

        JsonNode begin = data(mvc.perform(post(
                        "/api/v1/mcp-marketplace/connections/{id}/oauth/begin",
                        connection.path("id").asText())
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "redirectUri", "https://app.example/mcp/oauth/callback"))))
                .andExpect(status().isOk()).andReturn());
        assertThat(begin.path("authorizationServer").asText())
                .isEqualTo("https://login.acme.example/oauth");
        assertThat(begin.toString()).doesNotContain("client-secret");

        JsonNode grant = data(mvc.perform(post("/api/v1/mcp-marketplace/oauth/complete")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "state", begin.path("state").asText(),
                                "code", "authorization-code"))))
                .andExpect(status().isOk()).andReturn());
        assertThat(grant.path("state").asText()).isEqualTo("PENDING_VALIDATION");
        assertThat(grant.toString())
                .doesNotContain("http-access-secret", "http-refresh-secret", "client-secret");

        JsonNode connections = data(mvc.perform(get("/api/v1/mcp-marketplace/connections")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn());
        JsonNode stored = find(connections, "id", connection.path("id").asText());
        assertThat(stored.path("state").asText()).isEqualTo("PENDING_VALIDATION");
        assertThat(stored.path("authConfigured").asBoolean()).isTrue();
        assertThat(stored.toString()).doesNotContain("secret", "encryptedAuth");
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private static JsonNode find(JsonNode values, String field, String value) {
        for (JsonNode candidate : values) {
            if (candidate.path(field).asText().equals(value)) return candidate;
        }
        throw new AssertionError("Missing " + field + "=" + value);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
