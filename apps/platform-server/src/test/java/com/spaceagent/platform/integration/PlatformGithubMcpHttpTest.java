package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.project.domain.SourceRepository;
import com.spaceagent.platform.project.domain.Workspace;
import com.spaceagent.platform.project.domain.WorkspaceProvisioningGateway;
import com.spaceagent.platform.tooling.api.McpRemoteToolApplicationApi;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PlatformGithubMcpHttpTest.Config.class)
class PlatformGithubMcpHttpTest {
    private static final String CHECKOUT_HEADER =
            "Basic dXNlcjpwcml2YXRlLWh0dHAtY2hlY2tvdXQtc2VjcmV0";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void oauthDiscoveryImportAndPrivateWorkspaceUseMcpWithoutHttpSecretLeak() throws Exception {
        Config.observedAuthorization.set(null);
        JsonNode auth = data(mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "username", "github-mcp-http@example.com",
                                "password", "password123",
                                "displayName", "GitHub MCP"))))
                .andExpect(status().isOk()).andReturn());
        String token = auth.path("token").asText();
        JsonNode catalog = data(mvc.perform(get("/api/v1/mcp-marketplace/catalog")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn());
        String entry = "";
        for (JsonNode item : catalog) {
            if (item.path("slug").asText().equals("github")) entry = item.path("id").asText();
        }
        JsonNode installation = data(mvc.perform(post("/api/v1/mcp-marketplace/installations")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("entryId", entry, "scope", "USER"))))
                .andExpect(status().isCreated()).andReturn());
        JsonNode connection = data(mvc.perform(post("/api/v1/mcp-marketplace/connections")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "installationId", installation.path("id").asText(),
                                "endpointUrl", "https://mcp.github.example/mcp",
                                "authType", "OAUTH2", "auth", Map.of()))))
                .andExpect(status().isCreated()).andReturn());
        JsonNode begin = data(mvc.perform(post("/api/v1/github-mcp/connections/"
                        + connection.path("id").asText() + "/oauth/begin")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"redirectUri\":\"https://app.example/callback\"}"))
                .andExpect(status().isOk()).andReturn());
        data(mvc.perform(post("/api/v1/github-mcp/oauth/complete")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "state", begin.path("state").asText()))))
                .andExpect(status().isOk()).andReturn());
        JsonNode repositories = data(mvc.perform(get("/api/v1/github-mcp/connections/"
                        + connection.path("id").asText() + "/repositories")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn());
        assertThat(repositories.get(0).path("name").asText()).isEqualTo("demo");
        JsonNode discovered = data(mvc.perform(post("/api/v1/github-mcp/discover")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "connectionId", connection.path("id").asText(),
                                "githubUrl", "https://github.com/openai/openai-java"))))
                .andExpect(status().isOk()).andReturn());
        assertThat(discovered.path("owner").asText()).isEqualTo("openai");

        JsonNode project = data(mvc.perform(post("/api/v1/projects")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"MCP Project\"}"))
                .andExpect(status().isCreated()).andReturn());
        JsonNode task = data(mvc.perform(post("/api/v1/projects/"
                        + project.path("id").asText() + "/tasks")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Checkout\",\"goal\":\"Checkout private repository\"}"))
                .andExpect(status().isCreated()).andReturn());
        String importBody = json.writeValueAsString(Map.of(
                "connectionId", connection.path("id").asText(),
                "providerRepositoryId", "openai/openai-java"));
        JsonNode source = data(mvc.perform(post("/api/v1/projects/"
                        + project.path("id").asText() + "/sources/github-mcp")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "http-import-one")
                        .contentType(MediaType.APPLICATION_JSON).content(importBody))
                .andExpect(status().isCreated()).andReturn());
        JsonNode replay = data(mvc.perform(post("/api/v1/projects/"
                        + project.path("id").asText() + "/sources/github-mcp")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", "http-import-one")
                        .contentType(MediaType.APPLICATION_JSON).content(importBody))
                .andExpect(status().isCreated()).andReturn());
        assertThat(replay.path("id").asText()).isEqualTo(source.path("id").asText());
        assertThat(source.path("mcpConnectionId").asText())
                .isEqualTo(connection.path("id").asText());
        assertThat(source.path("mcpInvocationId").asText()).isNotBlank();
        assertThat(source.path("visibility").asText()).isEqualTo("PRIVATE");

        MvcResult workspaceResult = mvc.perform(post("/api/v1/projects/"
                        + project.path("id").asText() + "/workspaces")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "taskId", task.path("id").asText(),
                                "sourceRepositoryId", source.path("id").asText()))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode workspace = data(workspaceResult);
        assertThat(workspace.path("state").asText()).isEqualTo("READY");
        assertThat(Config.observedAuthorization.get()).isEqualTo(CHECKOUT_HEADER);
        assertThat(workspaceResult.getResponse().getContentAsString())
                .doesNotContain(CHECKOUT_HEADER, "private-http-checkout-secret");
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @TestConfiguration
    static class Config {
        private static final AtomicReference<String> observedAuthorization =
                new AtomicReference<>();

        @Bean
        @Primary
        McpRemoteToolApplicationApi fakeMcp() {
            return new McpRemoteToolApplicationApi() {
                public List<ToolView> listTools(String tenant, String user, String connection) {
                    return List.of();
                }

                public ToolResult callTool(CallCommand command) {
                    return switch (command.toolName()) {
                        case "github_begin_oauth" -> new ToolResult(false, Map.of(
                                "authorizationUrl", "https://github.com/login/oauth/authorize",
                                "sessionId", "provider-session"), "");
                        case "github_complete_oauth" -> new ToolResult(false, Map.of(
                                "accountId", "42", "login", "octocat",
                                "accessToken", "mcp-token"), "");
                        case "github_list_repositories" -> new ToolResult(false, Map.of(
                                "repositories", List.of(repository("octocat", "demo", false))), "");
                        case "github_get_repository" -> new ToolResult(false,
                                repository("openai", "openai-java", true), "");
                        case "github_prepare_checkout" -> new ToolResult(false, Map.of(
                                "cloneUrl", "https://github.com/openai/openai-java.git",
                                "authorizationHeader", CHECKOUT_HEADER,
                                "expiresAt", Instant.now().plusSeconds(300).toString()), "");
                        default -> new ToolResult(false,
                                repository("openai", "openai-java", false), "");
                    };
                }

                private Map<String, Object> repository(
                        String owner, String name, boolean privateRepository) {
                    return Map.of(
                            "id", owner + "/" + name,
                            "owner", owner,
                            "name", name,
                            "htmlUrl", "https://github.com/" + owner + "/" + name,
                            "cloneUrl", "https://github.com/" + owner + "/" + name + ".git",
                            "defaultBranch", "main",
                            "private", privateRepository,
                            "archived", false);
                }
            };
        }

        @Bean
        @Primary
        WorkspaceProvisioningGateway fakeWorkspaceGateway() {
            return new WorkspaceProvisioningGateway() {
                @Override
                public ProvisionedWorkspace provision(
                        Workspace workspace,
                        SourceRepository source,
                        String authorizationHeader) {
                    observedAuthorization.set(authorizationHeader);
                    return new ProvisionedWorkspace(
                            "managed:" + workspace.id(), "b".repeat(40));
                }

                @Override
                public void cleanup(Workspace workspace, SourceRepository source) {
                }
            };
        }
    }
}
