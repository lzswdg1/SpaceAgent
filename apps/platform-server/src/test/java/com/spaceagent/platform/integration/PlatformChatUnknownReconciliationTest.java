package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.inference.domain.InferenceExecutor;
import com.spaceagent.platform.inference.domain.ModelProviderConnectionTester;
import com.spaceagent.platform.inference.domain.ProviderConnectionProbeResult;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeToolExecutionApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeToolReconciliationApplicationApi;
import com.spaceagent.platform.runtime.domain.AgentRunState;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PlatformChatUnknownReconciliationTest.FixtureConfiguration.class)
class PlatformChatUnknownReconciliationTest {
    @Autowired PublishedAgentFixture publishedAgentFixture;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired RuntimeApplicationApi runtime;
    @Autowired FixtureToolBoundary toolBoundary;

    @Test
    void unknownWaitsAndVerifiedTerminalReplayContinuesTheSameRun() throws Exception {
        toolBoundary.reset();
        Identity identity = register("owner");
        String agentId = createAgent(identity, createProvider(identity));
        publishedAgentFixture.publish(agentId);

        JsonNode waiting = data(mockMvc.perform(post("/api/v1/chat/messages")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "agentId", agentId, "message", "unknown-flow"))))
                .andExpect(status().isOk()).andReturn());
        String runId = waiting.path("agentRunId").asText();
        String rootTaskId = waiting.path("rootTaskId").asText();

        assertThat(waiting.path("executionState").asText())
                .isEqualTo("WAITING_RECONCILIATION");
        assertThat(waiting.path("pendingToolRevision").asLong()).isEqualTo(2);
        assertThat(rootTaskId).isNotBlank();
        assertThat(waiting.path("rootTaskState").asText()).isEqualTo("IN_PROGRESS");
        assertThat(runtime.findRun(runId).orElseThrow().state())
                .isEqualTo(AgentRunState.WAITING_FOR_USER);
        assertThat(runtime.findLatestCheckpointByPhase(runId, "chat-tool-unknown")
                .orElseThrow().stateSnapshot()).contains("chat-tool-unknown/v1", "UNKNOWN");

        mockMvc.perform(post("/api/v1/chat/runs/" + runId
                                + "/tools/call-unknown/reconcile")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"reason\":\"stale\"}"))
                .andExpect(status().isConflict());
        assertThat(toolBoundary.reconciliations()).isZero();

        Identity intruder = register("intruder");
        mockMvc.perform(post("/api/v1/chat/runs/" + runId
                                + "/tools/call-unknown/reconcile")
                        .header("Authorization", bearer(intruder.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"reason\":\"cross tenant\"}"))
                .andExpect(status().isNotFound());
        assertThat(toolBoundary.reconciliations()).isZero();

        MvcResult completedResult = mockMvc.perform(post("/api/v1/chat/runs/" + runId
                                + "/tools/call-unknown/reconcile")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"reason\":\"postcondition verified\","
                                + "\"resolution\":\"SUCCEEDED\",\"verifierId\":\"client\","
                                + "\"evidence\":{\"raw\":\"client-selected-secret\"}}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode completed = data(completedResult);

        assertThat(completed.path("agentRunId").asText()).isEqualTo(runId);
        assertThat(completed.path("executionState").asText()).isEqualTo("COMPLETED");
        assertThat(completed.path("rootTaskId").asText()).isEqualTo(rootTaskId);
        assertThat(completed.path("rootTaskState").asText()).isEqualTo("COMPLETED");
        assertThat(completed.path("assistantMessage").asText())
                .isEqualTo("reconciled tool synthesis");
        assertThat(runtime.findRun(runId).orElseThrow().state())
                .isEqualTo(AgentRunState.COMPLETED);
        assertThat(toolBoundary.reconciliations()).isEqualTo(1);
        assertThat(toolBoundary.executions()).isEqualTo(2);
        assertThat(toolBoundary.lastReason()).isEqualTo("postcondition verified");
        assertThat(completed.has("resolution")).isFalse();
        assertThat(completed.has("verifierId")).isFalse();
        assertThat(completed.has("evidence")).isFalse();
        assertThat(completedResult.getResponse().getContentAsString())
                .doesNotContain("client-selected-secret", "\"verifierId\"");

        mockMvc.perform(post("/api/v1/chat/runs/" + runId
                                + "/tools/call-unknown/reconcile")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":2,\"reason\":\"duplicate\"}"))
                .andExpect(status().isConflict());
        assertThat(toolBoundary.executions()).isEqualTo(2);
    }

    private String createProvider(Identity identity) throws Exception {
        return data(mockMvc.perform(post("/api/v1/model-providers")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "unknown-provider",
                                "type", "openai",
                                "baseUrl", "https://example.com/v1",
                                "apiKey", "provider-fixture",
                                "models", List.of(Map.of(
                                        "modelId", "unknown-model",
                                        "displayName", "unknown-model",
                                        "maxContextTokens", 32768,
                                        "isDefault", true))))))
                .andExpect(status().isCreated()).andReturn()).path("id").asText();
    }

    private String createAgent(Identity identity, String providerId) throws Exception {
        return data(mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "unknown-agent",
                                "systemPrompt", "Use reconciled evidence",
                                "modelProviderId", providerId,
                                "modelId", "unknown-model",
                                "enabledToolIds", List.of("http_fetch"),
                                "networkEnabled", true))))
                .andExpect(status().isCreated()).andReturn()).path("id").asText();
    }

    private Identity register(String suffix) throws Exception {
        JsonNode auth = data(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "username", "chat-unknown-" + suffix + "@example.com",
                                "password", "password123",
                                "displayName", "chat-unknown"))))
                .andExpect(status().isOk()).andReturn());
        return new Identity(auth.path("tenantId").asText(), auth.path("token").asText());
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private String bearer(String token) { return "Bearer " + token; }

    private record Identity(String tenantId, String token) {}

    static final class FixtureToolBoundary {
        private final AtomicBoolean reconciled = new AtomicBoolean();
        private final AtomicInteger executions = new AtomicInteger();
        private final AtomicInteger reconciliations = new AtomicInteger();
        private String lastReason;

        void reset() {
            reconciled.set(false);
            executions.set(0);
            reconciliations.set(0);
            lastReason = null;
        }
        int executions() { return executions.get(); }
        int reconciliations() { return reconciliations.get(); }
        String lastReason() { return lastReason; }

        RuntimeToolExecutionApplicationApi.RuntimeToolResult execute(
                RuntimeToolExecutionApplicationApi.ExecuteRuntimeToolCommand command) {
            executions.incrementAndGet();
            return reconciled.get()
                    ? new RuntimeToolExecutionApplicationApi.RuntimeToolResult(
                            command.toolCallId(), command.toolId(), "SUCCEEDED",
                            "{\"verified\":true}", null, null, 3L)
                    : new RuntimeToolExecutionApplicationApi.RuntimeToolResult(
                            command.toolCallId(), command.toolId(), "UNKNOWN",
                            null, null, "ambiguous", 2L);
        }

        RuntimeToolReconciliationApplicationApi.ReconciliationView reconcile(
                RuntimeToolReconciliationApplicationApi.ReconcileCommand command) {
            reconciliations.incrementAndGet();
            lastReason = command.reason();
            reconciled.set(true);
            return new RuntimeToolReconciliationApplicationApi.ReconciliationView(
                    command.agentRunId(), command.toolCallId(), "http_fetch", "APPLIED",
                    "SUCCEEDED", 3, "fixture_postcondition", Map.of("verified", "true"),
                    Instant.parse("2026-09-06T00:00:00Z"));
        }
    }

    @TestConfiguration
    static class FixtureConfiguration {
        @Bean @Primary
        ModelProviderConnectionTester connectionTester() {
            return provider -> new ProviderConnectionProbeResult(
                    true, 5, List.of("unknown-model"), null);
        }

        @Bean
        FixtureToolBoundary fixtureToolBoundary() { return new FixtureToolBoundary(); }

        @Bean @Primary
        RuntimeToolExecutionApplicationApi runtimeToolExecution(FixtureToolBoundary boundary) {
            return boundary::execute;
        }

        @Bean @Primary
        RuntimeToolReconciliationApplicationApi runtimeToolReconciliation(
                FixtureToolBoundary boundary) {
            return boundary::reconcile;
        }

        @Bean @Primary
        InferenceExecutor unknownInferenceExecutor() {
            return request -> {
                boolean synthesis = request.messages().stream().anyMatch(message ->
                        message.content().contains("The authorized Tools have completed"));
                if (synthesis) {
                    return new InferenceExecutor.InferenceExecution(
                            "reconciled tool synthesis", 12, 6);
                }
                return new InferenceExecutor.InferenceExecution(
                        "", 8, 0, List.of(new InferenceExecutor.InferenceToolCall(
                                "call-unknown", "http_fetch",
                                "{\"url\":\"https://example.com/evidence\"}")));
            };
        }
    }
}
