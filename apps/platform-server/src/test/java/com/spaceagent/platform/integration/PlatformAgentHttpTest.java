package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.domain.AgentApiKeyRepository;
import com.spaceagent.platform.agent.domain.AgentRepository;
import com.spaceagent.platform.agent.infrastructure.memory.InMemoryAgentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Public HTTP coverage for Agent ownership and lifecycle. */
@SpringBootTest
@AutoConfigureMockMvc
class PlatformAgentHttpTest {

    private static final String INTERNAL_TOKEN = "local-dev-internal-token-change-me";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AgentApiKeyRepository apiKeyRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void springInjectsOnlyTheCanonicalAgentRepository() {
        assertThat(applicationContext.getBeansOfType(AgentRepository.class))
                .hasSize(1)
                .allSatisfy((name, repository) ->
                        assertThat(repository).isInstanceOf(InMemoryAgentRepository.class));
        assertThatThrownBy(() -> Class.forName(
                "com.spaceagent.platform.agent.infrastructure.persistence."
                        + "PostgresAgentDefinitionRepository"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName(
                "com.spaceagent.platform.agent.infrastructure.persistence."
                        + "PostgresAgentConfigurationRepository"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void crudConfigurationProviderAndKnowledgeBindingArePlatformOwned() throws Exception {
        Identity owner = register("phase2-owner@example.com");
        String providerId = createProvider(owner, "phase2-provider", "gpt-phase2");
        String documentId = createDocument(owner, "phase2-architecture.md");

        JsonNode created = createAgent(
                owner,
                "phase2-agent",
                providerId,
                "gpt-phase2",
                List.of(documentId));
        String agentId = created.path("id").asText();

        assertThat(created.path("systemPrompt").asText()).isEqualTo("Build safely");
        assertThat(created.path("revision").asLong()).isEqualTo(1);
        assertThat(created.has("configVersion")).isFalse();
        assertThat(created.path("modelProviderId").asText()).isEqualTo(providerId);
        assertThat(created.path("modelId").asText()).isEqualTo("gpt-phase2");
        assertThat(created.path("knowledgeBaseIds").get(0).asText()).isEqualTo(documentId);

        JsonNode listed = data(mockMvc.perform(get("/api/v1/agents")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(listed.findValuesAsText("id")).contains(agentId);

        JsonNode updated = data(mockMvc.perform(put("/api/v1/agents/" + agentId)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "phase2-agent-updated",
                                "systemPrompt", "Build and verify",
                                "temperature", 0.3))))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(updated.path("name").asText()).isEqualTo("phase2-agent-updated");
        assertThat(updated.path("systemPrompt").asText()).isEqualTo("Build and verify");
        assertThat(updated.path("modelProviderId").asText()).isEqualTo(providerId);
        assertThat(updated.path("revision").asLong()).isEqualTo(2);
        assertThat(updated.has("configVersion")).isFalse();

        JsonNode bindings = data(mockMvc.perform(get("/api/v1/agents/" + agentId + "/knowledge-bindings")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(bindings.get(0).asText()).isEqualTo(documentId);

        mockMvc.perform(get("/internal/agents/" + agentId + "/runtime-config")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .header("X-User-Id", owner.userId())
                .header("X-Tenant-Id", owner.tenantId())
                .header("X-Tenant-Role", "OWNER"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/agents/" + agentId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/agents/" + agentId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownershipIsolationAppliesToAgentProviderAndKnowledgeReferences() throws Exception {
        Identity alice = register("phase2-alice@example.com");
        Identity bob = register("phase2-bob@example.com");
        String aliceProvider = createProvider(alice, "alice-provider", "alice-model");
        String aliceDocument = createDocument(alice, "alice-private.md");
        String aliceAgent = createAgent(
                alice, "alice-agent", aliceProvider, "alice-model", List.of(aliceDocument))
                .path("id").asText();

        mockMvc.perform(get("/api/v1/agents/" + aliceAgent)
                        .header("Authorization", bearer(bob.token())))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/agents/" + aliceAgent + "/keys")
                        .header("Authorization", bearer(bob.token())))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(bob.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "foreign-provider-agent",
                                "modelProviderId", aliceProvider,
                                "modelId", "alice-model"))))
                .andExpect(status().isBadRequest());

        String bobProvider = createProvider(bob, "bob-provider", "bob-model");
        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(bob.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "foreign-knowledge-agent",
                                "modelProviderId", bobProvider,
                                "modelId", "bob-model",
                                "knowledgeBaseIds", List.of(aliceDocument)))))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(bob.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void organizationMemberSaveRequiresCurrentOwnerApprovalWithoutAgentVersion() throws Exception {
        Identity owner = register("organization-agent-owner@example.com");
        Identity member = register("organization-agent-member@example.com");
        String organizationId = createOrganization(
                owner, "Organization Agent", "organization-agent-approval");
        inviteAndAccept(owner, member, organizationId, "MEMBER");
        owner = switchOrganization(owner, organizationId);
        member = switchOrganization(member, organizationId);

        String ownerAgent = data(mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Owner Agent", "systemPrompt", "original"))))
                .andExpect(status().isCreated())
                .andReturn()).path("id").asText();
        String memberAgent = data(mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(member.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Member Agent", "systemPrompt", "member original"))))
                .andExpect(status().isCreated())
                .andReturn()).path("id").asText();

        JsonNode organizationAgents = data(mockMvc.perform(get("/api/v1/agents/organization")
                        .header("Authorization", bearer(member.token())))
                .andExpect(status().isOk())
                .andReturn());
        JsonNode ownerAccess = java.util.stream.StreamSupport.stream(
                        organizationAgents.spliterator(), false)
                .filter(value -> value.path("agent").path("id").asText().equals(ownerAgent))
                .findFirst().orElseThrow();
        assertThat(ownerAccess.path("writeMode").asText())
                .isEqualTo("OWNER_APPROVAL_REQUIRED");

        JsonNode pending = data(mockMvc.perform(put("/api/v1/agents/" + ownerAgent)
                        .header("Authorization", bearer(member.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "systemPrompt", "member proposal"))))
                .andExpect(status().isAccepted())
                .andReturn());
        assertThat(pending.path("outcome").asText()).isEqualTo("PENDING_APPROVAL");
        String requestId = pending.path("changeRequest").path("id").asText();
        long requestRevision = pending.path("changeRequest").path("revision").asLong();
        assertThat(data(mockMvc.perform(get("/api/v1/agents/" + ownerAgent)
                        .header("Authorization", bearer(member.token())))
                .andExpect(status().isOk()).andReturn()).path("systemPrompt").asText())
                .isEqualTo("original");
        assertThat(data(mockMvc.perform(get("/api/v1/agents/" + ownerAgent
                                + "/configuration-changes")
                        .header("Authorization", bearer(member.token())))
                .andExpect(status().isOk()).andReturn()).get(0).path("id").asText())
                .isEqualTo(requestId);

        mockMvc.perform(post("/api/v1/agents/" + ownerAgent + "/configuration-changes/"
                        + requestId + "/decision")
                        .header("Authorization", bearer(member.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedRevision", requestRevision, "decision", "APPROVE"))))
                .andExpect(status().isForbidden());

        JsonNode applied = data(mockMvc.perform(post("/api/v1/agents/" + ownerAgent
                                + "/configuration-changes/" + requestId + "/decision")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedRevision", requestRevision,
                                "decision", "APPROVE", "note", "approved"))))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(applied.path("state").asText()).isEqualTo("APPLIED");
        assertThat(applied.path("proposal").isNull()).isTrue();
        assertThat(data(mockMvc.perform(get("/api/v1/agents/" + ownerAgent)
                        .header("Authorization", bearer(member.token())))
                .andExpect(status().isOk()).andReturn()).path("systemPrompt").asText())
                .isEqualTo("member proposal");

        JsonNode ownerDirect = data(mockMvc.perform(put("/api/v1/agents/" + memberAgent)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "systemPrompt", "owner direct"))))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(ownerDirect.path("systemPrompt").asText()).isEqualTo("owner direct");
        assertThat(ownerDirect.path("ownerId").asText()).isEqualTo(member.userId());
    }

    @Test
    void providerModelTestExecutesBoundedInferenceProbe() throws Exception {
        Identity owner = register("provider-model-test@example.com");
        String providerId = createProvider(owner, "probe-provider", "probe-model");

        JsonNode result = data(mockMvc.perform(post(
                                "/api/v1/model-providers/" + providerId
                                        + "/models/probe-model/test")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("modelId").asText()).isEqualTo("probe-model");
        assertThat(result.path("responsePreview").asText()).isEqualTo("noop-inference:probe-model");
        assertThat(result.path("inputTokens").asInt()).isPositive();
        assertThat(result.path("outputTokens").asInt()).isPositive();
    }

    @Test
    void providerModelTestAcceptsSlashBearingExternalModelIdInJsonBody() throws Exception {
        Identity owner = register("provider-slash-model-test@example.com");
        String modelId = "ZHIPU/GLM-5.3-Flash";
        String providerId = createProvider(owner, "slash-probe-provider", modelId);

        JsonNode result = data(mockMvc.perform(post(
                                "/api/v1/model-providers/" + providerId + "/models/test")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("modelId", modelId))))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(result.path("success").asBoolean()).isTrue();
        assertThat(result.path("modelId").asText()).isEqualTo(modelId);
        assertThat(result.path("responsePreview").asText())
                .isEqualTo("noop-inference:" + modelId);
    }

    @Test
    void apiKeyIsReturnedOnceStoredAsHashVerifiedAndRevoked() throws Exception {
        Identity owner = register("phase2-key-owner@example.com");
        String providerId = createProvider(owner, "key-provider", "key-model");
        String agentId = createAgent(owner, "key-agent", providerId, "key-model", List.of())
                .path("id").asText();

        JsonNode created = data(mockMvc.perform(post("/api/v1/agents/" + agentId + "/keys")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "ci-key",
                                "scopes", List.of("CHAT"),
                                "expiresAt", Instant.parse("2030-01-01T00:00:00Z")))))
                .andExpect(status().isCreated())
                .andReturn());
        String rawKey = created.path("rawKey").asText();
        String keyId = created.path("apiKey").path("id").asText();
        assertThat(rawKey).startsWith("agk_");
        assertThat(created.toString()).doesNotContain("keyHash");

        var persisted = apiKeyRepository.findById(keyId).orElseThrow();
        assertThat(persisted.keyHash()).hasSize(64).isNotEqualTo(rawKey);

        JsonNode verified = data(mockMvc.perform(post("/internal/agents/api-keys/verify")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("rawKey", rawKey))))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(verified.path("agentId").asText()).isEqualTo(agentId);
        assertThat(verified.path("tenantId").asText()).isEqualTo(owner.tenantId());

        JsonNode listed = data(mockMvc.perform(get("/api/v1/agents/" + agentId + "/keys")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(listed.get(0).path("lastUsedAt").asText()).isNotBlank();
        assertThat(listed.toString()).doesNotContain(rawKey).doesNotContain("keyHash");

        mockMvc.perform(delete("/api/v1/agents/" + agentId + "/keys/" + keyId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk());
        mockMvc.perform(post("/internal/agents/api-keys/verify")
                        .header("X-Internal-Token", INTERNAL_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("rawKey", rawKey))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void conversationOwnerCanSwitchOnlyToAnOwnedActiveAgent() throws Exception {
        Identity owner = register("conversation-switch-owner@example.com");
        Identity foreign = register("conversation-switch-foreign@example.com");
        String firstAgentId = data(mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "First Agent"))))
                .andExpect(status().isCreated())
                .andReturn()).path("id").asText();
        String nextAgentId = data(mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Next Agent"))))
                .andExpect(status().isCreated())
                .andReturn()).path("id").asText();
        String foreignAgentId = data(mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(foreign.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "Foreign Agent"))))
                .andExpect(status().isCreated())
                .andReturn()).path("id").asText();
        String conversationId = data(mockMvc.perform(post("/api/v1/chat/conversations")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "agentId", firstAgentId,
                                "name", "Switchable conversation"))))
                .andExpect(status().isOk())
                .andReturn()).path("conversationId").asText();

        JsonNode switched = data(mockMvc.perform(put(
                                "/api/v1/chat/conversations/" + conversationId + "/agent")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("agentId", nextAgentId))))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(switched.path("agentId").asText()).isEqualTo(nextAgentId);
        mockMvc.perform(put("/api/v1/chat/conversations/" + conversationId + "/agent")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("agentId", foreignAgentId))))
                .andExpect(status().isNotFound());
        mockMvc.perform(put("/api/v1/chat/conversations/" + conversationId + "/agent")
                        .header("Authorization", bearer(foreign.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("agentId", foreignAgentId))))
                .andExpect(status().isNotFound());
    }

    @Test
    void agentCapabilitiesComeFromTheExecutableRuntimeCatalog() throws Exception {
        Identity owner = register("agent-capability-owner@example.com");

        JsonNode catalog = data(mockMvc.perform(get("/api/v1/tooling/capabilities")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(catalog.path("tools").size()).isEqualTo(20);
        assertThat(catalog.path("tools").get(0).path("id").asText()).isEqualTo("echo");
        assertThat(catalog.path("tools")).anySatisfy(tool -> {
            assertThat(tool.path("id").asText()).isEqualTo("file_read");
            assertThat(tool.path("available").asBoolean()).isFalse();
            assertThat(tool.path("requiresWorkspace").asBoolean()).isTrue();
        });
        assertThat(catalog.path("tools")).anySatisfy(tool -> {
            assertThat(tool.path("id").asText()).isEqualTo("web_search");
            assertThat(tool.path("available").asBoolean()).isFalse();
            assertThat(tool.path("requiresNetwork").asBoolean()).isTrue();
        });
        assertThat(catalog.path("skills").size()).isZero();
        assertThat(catalog.path("sandbox").path("mode").asText()).isEqualTo("IN_PROCESS");
        assertThat(catalog.path("sandbox").path("isolation").asText())
                .isEqualTo("PROCESS_COMPATIBILITY");
        assertThat(catalog.path("sandbox").path("containerized").asBoolean()).isFalse();

        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Echo Agent",
                                "enabledToolIds", List.of("echo")))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Unbound Knowledge Agent",
                                "ragEnabled", false,
                                "enabledToolIds", List.of("knowledge_search")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("AGENT_KNOWLEDGE_TOOL_REQUIRES_BINDING"));
        String knowledgeId = createDocument(owner, "knowledge-bound.md");
        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Bound Knowledge Agent",
                                "ragEnabled", true,
                                "knowledgeBaseIds", List.of(knowledgeId),
                                "enabledToolIds", List.of("knowledge_search")))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Workspace Agent",
                                "enabledToolIds", List.of("file_read", "git_diff")))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Unavailable Search Agent",
                                "enabledToolIds", List.of("web_search")))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Unknown Tool Agent",
                                "enabledToolIds", List.of("not-registered")))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Unknown Skill Agent",
                                "skillIds", List.of("not-registered")))))
                .andExpect(status().isBadRequest());
    }

    private JsonNode createAgent(
            Identity identity,
            String name,
            String providerId,
            String modelId,
            List<String> knowledgeIds) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", name);
        request.put("systemPrompt", "Build safely");
        request.put("modelProviderId", providerId);
        request.put("modelId", modelId);
        request.put("temperature", 0.2);
        request.put("maxTokens", 4096);
        request.put("maxTurns", 25);
        request.put("permissionMode", "private");
        request.put("memoryEnabled", true);
        request.put("ragEnabled", !knowledgeIds.isEmpty());
        request.put("networkEnabled", false);
        request.put("knowledgeBaseIds", knowledgeIds);
        return data(mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private String createProvider(Identity identity, String name, String modelId) throws Exception {
        return data(mockMvc.perform(post("/api/v1/model-providers")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "type", "openai",
                                "baseUrl", "https://example.com/v1",
                                "apiKey", "provider-secret",
                                "models", List.of(Map.of(
                                        "modelId", modelId,
                                        "displayName", modelId,
                                        "maxContextTokens", 32768,
                                        "isDefault", true))))))
                .andExpect(status().isCreated())
                .andReturn()).path("id").asText();
    }

    private String createDocument(Identity identity, String name) throws Exception {
        return data(mockMvc.perform(post("/api/v1/knowledge/documents")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name,
                                "contentType", "text/markdown",
                                "storageLocation", "storage://" + name))))
                .andExpect(status().isOk())
                .andReturn()).path("id").asText();
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

    private String createOrganization(Identity owner, String name, String slug) throws Exception {
        return data(mockMvc.perform(post("/api/v1/organizations")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name, "slug", slug))))
                .andExpect(status().isCreated()).andReturn()).path("id").asText();
    }

    private void inviteAndAccept(
            Identity owner, Identity invitee, String organizationId, String role) throws Exception {
        JsonNode invitation = data(mockMvc.perform(post("/api/v1/organizations/" + organizationId
                                + "/invitations")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "organization-agent-member@example.com",
                                "role", role, "expiresInHours", 24))))
                .andExpect(status().isCreated()).andReturn());
        mockMvc.perform(post("/api/v1/organization-invitations/accept")
                        .header("Authorization", bearer(invitee.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", invitation.path("token").asText()))))
                .andExpect(status().isOk());
    }

    private Identity switchOrganization(Identity identity, String organizationId) throws Exception {
        JsonNode switched = data(mockMvc.perform(post("/api/v1/organizations/" + organizationId + "/switch")
                        .header("Authorization", bearer(identity.token())))
                .andExpect(status().isOk()).andReturn());
        return new Identity(identity.userId(), organizationId, switched.path("token").asText());
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record Identity(String userId, String tenantId, String token) {
    }
}
