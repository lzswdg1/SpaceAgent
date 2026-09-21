package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PlatformAutomationHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void scheduleApprovalContinuationAndPreparedChatExecuteEndToEnd() throws Exception {
        Identity owner = register("automation-owner@example.com");
        String providerId = createProvider(owner);
        String agentId = createAgent(owner, providerId);
        String base = "/api/v1/agents/" + agentId + "/scheduled-tasks";

        JsonNode created = data(mockMvc.perform(post(base)
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "agentId", agentId,
                                "description", "Daily summary",
                                "prompt", "Summarize Automation state",
                                "type", "one_time",
                                "scheduledAt", Instant.now().plusSeconds(86_400).toString(),
                                "timezone", "UTC",
                                "maxRetries", 1))))
                .andExpect(status().isCreated())
                .andReturn());
        String scheduleId = created.path("id").asText();
        assertThat(created.path("status").asText()).isEqualTo("active");
        assertThat(created.path("bullJobId").isNull()).isTrue();

        JsonNode paused = data(mockMvc.perform(post(base + "/" + scheduleId + "/pause")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk()).andReturn());
        assertThat(paused.path("status").asText()).isEqualTo("paused");
        mockMvc.perform(post(base + "/" + scheduleId + "/resume")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk());

        JsonNode policy = data(mockMvc.perform(get("/api/v1/governance/policy")
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk()).andReturn());
        mockMvc.perform(put("/api/v1/governance/policy")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requireCodingFileApproval", false,
                                "requireCommandApproval", false,
                                "requireAutomationApproval", true,
                                "requireNetworkApproval", false,
                                "requireSourceMergeApproval", false,
                                "separationOfDuties", false,
                                "approvalTtlSeconds", 3600,
                                "expectedRevision", policy.path("revision").asLong()))))
                .andExpect(status().isOk());

        JsonNode waiting = data(mockMvc.perform(post(base + "/" + scheduleId + "/trigger")
                        .header("Authorization", bearer(owner.token()))
                        .header("Idempotency-Key", "automation-http-once"))
                .andExpect(status().isOk()).andReturn());
        assertThat(waiting.path("status").asText()).isEqualTo("waiting_approval");
        String executionId = waiting.path("id").asText();
        String approvalId = waiting.path("approvalId").asText();

        mockMvc.perform(post("/api/v1/governance/approvals/" + approvalId + "/decision")
                        .header("Authorization", bearer(owner.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\",\"note\":\"scheduled safely\"}"))
                .andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            JsonNode rows = data(mockMvc.perform(get(base + "/" + scheduleId + "/executions")
                            .header("Authorization", bearer(owner.token())))
                    .andExpect(status().isOk()).andReturn());
            JsonNode execution = rows.get(0);
            assertThat(execution.path("id").asText()).isEqualTo(executionId);
            assertThat(execution.path("status").asText()).isEqualTo("success");
            assertThat(execution.path("agentRunId").asText()).isNotBlank();
            assertThat(execution.path("sessionId").asText()).isNotBlank();
        });

        JsonNode replay = data(mockMvc.perform(post(base + "/" + scheduleId + "/trigger")
                        .header("Authorization", bearer(owner.token()))
                        .header("Idempotency-Key", "automation-http-once"))
                .andExpect(status().isOk()).andReturn());
        assertThat(replay.path("id").asText()).isEqualTo(executionId);
        assertThat(replay.path("status").asText()).isEqualTo("success");

        Identity outsider = register("automation-outsider@example.com");
        mockMvc.perform(get(base).header("Authorization", bearer(outsider.token())))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(base + "/" + scheduleId)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk());
        assertThat(data(mockMvc.perform(get(base)
                        .header("Authorization", bearer(owner.token())))
                .andExpect(status().isOk()).andReturn())).isEmpty();
    }


    private Identity register(String username) throws Exception {
        JsonNode auth = data(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", "password123",
                                "displayName", username))))
                .andExpect(status().isOk()).andReturn());
        return new Identity(
                auth.path("userId").asText(), auth.path("tenantId").asText(),
                auth.path("token").asText());
    }

    private String createProvider(Identity identity) throws Exception {
        return data(mockMvc.perform(post("/api/v1/model-providers")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "automation-provider",
                                "type", "openai",
                                "baseUrl", "https://example.com/v1",
                                "apiKey", "provider-secret",
                                "models", List.of(Map.of(
                                        "modelId", "automation-model",
                                        "displayName", "Automation Model",
                                        "maxContextTokens", 32768,
                                        "isDefault", true))))))
                .andExpect(status().isCreated()).andReturn()).path("id").asText();
    }

    private String createAgent(Identity identity, String providerId) throws Exception {
        return data(mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "automation-agent",
                                "systemPrompt", "Execute scheduled prompts safely",
                                "modelProviderId", providerId,
                                "modelId", "automation-model"))))
                .andExpect(status().isCreated()).andReturn()).path("id").asText();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record Identity(String userId, String tenantId, String token) {}
}
