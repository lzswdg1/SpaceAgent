package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.domain.McpConnectionProbeGateway;
import com.spaceagent.platform.tooling.infrastructure.OfficialSdkMcpRemoteToolGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PlatformMcpMarketplaceHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean OfficialSdkMcpRemoteToolGateway probe;

    @Test
    void catalogVersionsInstallAndConnectionNeverReturnSecrets() throws Exception {
        when(probe.probe(any(), any())).thenReturn(new McpConnectionProbeGateway.ProbeResult(
                "2025-11-25", "http-fixture", "HTTP Fixture", "1.0.0", null,
                Map.of("tools", Map.of("listChanged", false)),
                List.of(new McpConnectionProbeGateway.ProbeTool(
                        "lookup", "Lookup", "Lookup HTTP fixture data",
                        Map.of("type", "object"), Map.of("type", "object"),
                        true, false, true, false))));
        JsonNode authentication = data(mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "username", "mcp-http@example.com",
                                "password", "password123",
                                "displayName", "MCP"))))
                .andExpect(status().isOk())
                .andReturn());
        String token = authentication.path("token").asText();

        JsonNode catalog = data(mvc.perform(get("/api/v1/mcp-marketplace/catalog")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode github = find(catalog, "slug", "github");
        JsonNode custom = find(catalog, "slug", "custom-streamable-http");
        assertThat(github.path("publisherNamespace").asText()).isEqualTo("com.github");
        assertThat(github.path("sourceType").asText()).isEqualTo("BUILT_IN");
        assertThat(github.path("trustTier").asText()).isEqualTo("PLATFORM_CURATED");
        assertThat(github.path("lifecycle").asText()).isEqualTo("ACTIVE");
        assertThat(github.path("currentVersion").asText()).isEqualTo("1.0.0");
        assertThat(github.path("currentManifestSha256").asText())
                .matches("[0-9a-f]{64}");

        JsonNode versions = data(mvc.perform(get(
                        "/api/v1/mcp-marketplace/catalog/{entryId}/versions",
                        github.path("id").asText())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0).path("id").asText())
                .isEqualTo(github.path("currentVersionId").asText());
        assertThat(versions.get(0).path("lifecycleState").asText()).isEqualTo("APPROVED");
        assertThat(versions.get(0).path("transports")).hasSize(1);
        assertThat(versions.get(0).at("/transports/0/transportType").asText())
                .isEqualTo("STREAMABLE_HTTP");
        JsonNode version = data(mvc.perform(get(
                        "/api/v1/mcp-marketplace/catalog/{entryId}/versions/{versionId}",
                        github.path("id").asText(), github.path("currentVersionId").asText())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(version.path("manifestSha256").asText())
                .isEqualTo(github.path("currentManifestSha256").asText());

        mvc.perform(post("/api/v1/mcp-marketplace/installations")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "entryId", github.path("id").asText(),
                                "serverVersionId", custom.path("currentVersionId").asText(),
                                "scope", "USER"))))
                .andExpect(status().isConflict());

        JsonNode installation = data(mvc.perform(post("/api/v1/mcp-marketplace/installations")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "entryId", github.path("id").asText(),
                                "scope", "USER"))))
                .andExpect(status().isCreated())
                .andReturn());
        assertThat(installation.path("serverVersionId").asText())
                .isEqualTo(github.path("currentVersionId").asText());
        assertThat(installation.path("serverVersion").asText()).isEqualTo("1.0.0");

        JsonNode connection = data(mvc.perform(post("/api/v1/mcp-marketplace/connections")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "installationId", installation.path("id").asText(),
                                "endpointUrl", "https://mcp.github.example/rpc",
                                "authType", "OAUTH2",
                                "auth", Map.of("token", "http-secret")))))
                .andExpect(status().isCreated())
                .andReturn());
        assertThat(connection.path("state").asText()).isEqualTo("PENDING_VALIDATION");
        assertThat(connection.path("authConfigured").asBoolean()).isTrue();
        assertThat(connection.toString())
                .doesNotContain("http-secret")
                .doesNotContain("encryptedAuth");

        JsonNode qualified = data(mvc.perform(post(
                        "/api/v1/mcp-marketplace/connections/{id}/qualification",
                        connection.path("id").asText())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(qualified.path("state").asText()).isEqualTo("ACTIVE");
        assertThat(qualified.path("protocolVersion").asText()).isEqualTo("2025-11-25");
        assertThat(qualified.path("toolCount").asInt()).isEqualTo(1);
        assertThat(qualified.toString()).doesNotContain("http-secret");

        JsonNode statusView = data(mvc.perform(get(
                        "/api/v1/mcp-marketplace/connections/{id}/qualification",
                        connection.path("id").asText())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(statusView.path("snapshotId").asText())
                .isEqualTo(qualified.path("snapshotId").asText());
        JsonNode observations = data(mvc.perform(get(
                        "/api/v1/mcp-marketplace/connections/{id}/health-observations",
                        connection.path("id").asText())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(observations).hasSize(1);
        assertThat(observations.get(0).path("outcome").asText()).isEqualTo("SUCCEEDED");
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
