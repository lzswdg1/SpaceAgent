package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.inference.domain.ModelProviderConnectionTester;
import com.spaceagent.platform.inference.domain.ProviderConnectionProbeResult;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PlatformModelPoolHttpTest.ConnectionTestConfiguration.class)
class PlatformModelPoolHttpTest {
    @Autowired PublishedAgentFixture publishedAgentFixture;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void testedProvidersBecomeOrganizationVisiblePriorityPoolCandidates() throws Exception {
        Identity owner = register("modelpool-owner@example.com");
        Identity member = register("modelpool-member@example.com");
        addOrganizationMember(owner, member.userId());
        String memberOrganizationToken = switchOrganization(
                member.token(), owner.organizationId());

        JsonNode firstProvider = createProvider(owner.token(), "pool-provider-a", "model-a");
        JsonNode secondProvider = createProvider(owner.token(), "pool-provider-b", "model-b");
        assertThat(firstProvider.path("connectionStatus").asText()).isEqualTo("UNTESTED");
        testProvider(owner.token(), firstProvider.path("id").asText());

        JsonNode pool = data(mockMvc.perform(post("/api/v1/model-pools")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "organization-pool",
                                "visibility", "ORGANIZATION",
                                "routingStrategy", "PRIORITY",
                                "fallbackEnabled", true))))
                .andExpect(status().isCreated())
                .andReturn());
        String poolId = pool.path("id").asText();
        String firstModelRef = firstModelRef(owner.token(), firstProvider.path("id").asText());
        String secondModelRef = firstModelRef(owner.token(), secondProvider.path("id").asText());

        addPoolMember(
                owner.token(), poolId, firstProvider.path("id").asText(), firstModelRef, 20);
        mockMvc.perform(post("/api/v1/model-pools/" + poolId + "/members")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(poolMemberJson(
                                secondProvider.path("id").asText(), secondModelRef, 10)))
                .andExpect(status().isConflict());
        testProvider(owner.token(), secondProvider.path("id").asText());
        addPoolMember(
                owner.token(), poolId, secondProvider.path("id").asText(), secondModelRef, 10);

        JsonNode activated = data(mockMvc.perform(post(
                                "/api/v1/model-pools/" + poolId + "/activate")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(activated.path("status").asText()).isEqualTo("ACTIVE");

        JsonNode listed = data(mockMvc.perform(get("/api/v1/model-pools")
                        .header("Authorization", bearer(memberOrganizationToken)))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(listed.findValuesAsText("id")).contains(poolId);
        JsonNode resolution = data(mockMvc.perform(get(
                                "/api/v1/model-pools/" + poolId + "/resolution")
                        .header("Authorization", bearer(memberOrganizationToken)))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(resolution.path("candidates").get(0).path("modelId").asText())
                .isEqualTo("model-b");
        assertThat(resolution.path("candidates").get(1).path("modelId").asText())
                .isEqualTo("model-a");

        mockMvc.perform(delete("/api/v1/model-providers/" + firstProvider.path("id").asText())
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isConflict());
    }

    @Test
    void privatePoolIsHiddenFromOtherOrganizationMember() throws Exception {
        Identity owner = register("private-pool-owner@example.com");
        Identity member = register("private-pool-member@example.com");
        addOrganizationMember(owner, member.userId());
        String memberToken = switchOrganization(member.token(), owner.organizationId());

        String poolId = data(mockMvc.perform(post("/api/v1/model-pools")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "private-model-pool",
                                "visibility", "PRIVATE",
                                "routingStrategy", "PRIORITY",
                                "fallbackEnabled", false))))
                .andExpect(status().isCreated())
                .andReturn()).path("id").asText();

        mockMvc.perform(get("/api/v1/model-pools/" + poolId)
                        .header("Authorization", bearer(memberToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void agentVersionBindsActivePoolAndRejectsMixedOrDraftSelection() throws Exception {
        Identity owner = register("agent-pool-owner@example.com");
        JsonNode provider = createProvider(owner.token(), "agent-pool-provider", "agent-model");
        String providerId = provider.path("id").asText();
        testProvider(owner.token(), providerId);

        String activePoolId = data(mockMvc.perform(post("/api/v1/model-pools")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "agent-active-pool",
                                "visibility", "ORGANIZATION",
                                "routingStrategy", "PRIORITY",
                                "fallbackEnabled", true))))
                .andExpect(status().isCreated())
                .andReturn()).path("id").asText();
        addPoolMember(
                owner.token(), activePoolId, providerId,
                firstModelRef(owner.token(), providerId), 10);
        mockMvc.perform(post("/api/v1/model-pools/" + activePoolId + "/activate")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk());

        JsonNode agent = data(mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "pool-bound-agent",
                                "systemPrompt", "Use the pool",
                                "modelPoolId", activePoolId))))
                .andExpect(status().isCreated())
                .andReturn());
        assertThat(agent.path("modelPoolId").asText()).isEqualTo(activePoolId);
        assertThat(agent.path("modelProviderId").isNull()).isTrue();
        assertThat(agent.path("modelId").isNull()).isTrue();
        publishedAgentFixture.publish(agent.path("id").asText());

        JsonNode runtime = data(mockMvc.perform(get(
                                "/internal/agents/" + agent.path("id").asText() + "/runtime-config")
                        .header("X-Internal-Token", "local-dev-internal-token-change-me")
                        .header("X-User-Id", owner.userId())
                        .header("X-Tenant-Id", owner.organizationId())
                        .header("X-Tenant-Role", "OWNER"))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(runtime.path("modelPoolId").asText()).isEqualTo(activePoolId);

        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "mixed-agent",
                                "modelPoolId", activePoolId,
                                "modelProviderId", providerId,
                                "modelId", "agent-model"))))
                .andExpect(status().isBadRequest());

        String draftPoolId = data(mockMvc.perform(post("/api/v1/model-pools")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "agent-draft-pool",
                                "visibility", "PRIVATE",
                                "routingStrategy", "PRIORITY",
                                "fallbackEnabled", false))))
                .andExpect(status().isCreated())
                .andReturn()).path("id").asText();
        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "draft-pool-agent",
                                "modelPoolId", draftPoolId))))
                .andExpect(status().isConflict());
    }

    private JsonNode createProvider(
            String token,
            String name,
            String modelId) throws Exception {
        return data(mockMvc.perform(post("/api/v1/model-providers")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "type", "openai-compatible",
                                "baseUrl", "https://example.com/v1",
                                "apiKey", "provider-secret",
                                "authType", "bearer",
                                "enabled", true,
                                "isDefault", false,
                                "models", List.of(Map.of(
                                        "modelId", modelId,
                                        "displayName", modelId,
                                        "maxContextTokens", 32768,
                                        "isDefault", true))))))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private void testProvider(String token, String providerId) throws Exception {
        JsonNode result = data(mockMvc.perform(post(
                                "/api/v1/model-providers/" + providerId + "/test")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("status").asText()).isEqualTo("ACTIVE");
    }

    private String firstModelRef(String token, String providerId) throws Exception {
        return data(mockMvc.perform(get("/api/v1/model-providers/" + providerId + "/models")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn()).get(0).path("id").asText();
    }

    private void addPoolMember(
            String token,
            String poolId,
            String providerId,
            String providerModelId,
            int priority) throws Exception {
        mockMvc.perform(post("/api/v1/model-pools/" + poolId + "/members")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(poolMemberJson(providerId, providerModelId, priority)))
                .andExpect(status().isCreated());
    }

    private String poolMemberJson(
            String providerId,
            String providerModelId,
            int priority) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "providerId", providerId,
                "providerModelId", providerModelId,
                "priority", priority,
                "weight", 1));
    }

    private Identity register(String username) throws Exception {
        JsonNode auth = data(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "password123",
                                "displayName", username))))
                .andExpect(status().isOk())
                .andReturn());
        return new Identity(
                auth.path("userId").asText(),
                auth.path("tenantId").asText(),
                auth.path("token").asText());
    }

    private void addOrganizationMember(Identity owner, String userId) throws Exception {
        mockMvc.perform(post("/api/v1/organizations/" + owner.organizationId() + "/members")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "userId", userId,
                                "role", "MEMBER"))))
                .andExpect(status().isCreated());
    }

    private String switchOrganization(String token, String organizationId) throws Exception {
        return data(mockMvc.perform(post(
                                "/api/v1/organizations/" + organizationId + "/switch")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn()).path("token").asText();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private record Identity(String userId, String organizationId, String token) {
    }

    @TestConfiguration
    static class ConnectionTestConfiguration {

        @Bean
        @Primary
        ModelProviderConnectionTester successfulProviderConnectionTester() {
            return provider -> new ProviderConnectionProbeResult(
                    true, 5, List.of("model-a", "model-b"), null);
        }
    }
}
