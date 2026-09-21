package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.governance.api.GovernanceApprovalRequiredException;
import com.spaceagent.platform.inference.api.InferenceExecutionApi;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeToolExecutionApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeToolReconciliationApplicationApi;
import com.spaceagent.platform.runtime.domain.MultiAgentOrchestrationPort;
import com.spaceagent.platform.runtime.domain.multiagent.MultiAgentOrchestrationResponse;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "platform.chat.automatic-planning-enabled=true")
@AutoConfigureMockMvc
@Import(PlatformChatPlannedToolResumeTest.Configuration.class)
class PlatformChatPlannedToolResumeTest {
    @Autowired PublishedAgentFixture publishedAgentFixture;
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RuntimeApplicationApi runtimeApi;

    @Test
    void toolApprovalCheckpointRetainsPlanProgressAndContinuesRemainingDag() throws Exception {
        String username = "planned-tool-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        JsonNode auth = data(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", "password123",
                                "displayName", "Planned Tool"))))
                .andExpect(status().isOk()).andReturn());
        String token = auth.path("token").asText();
        String agentId = data(mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"planned-tool-agent\"}"))
                .andExpect(status().isCreated()).andReturn()).path("id").asText();
        publishedAgentFixture.publish(agentId);
        JsonNode waiting = data(mockMvc.perform(post("/api/v1/chat/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "agentId", agentId, "message", "execute planned tool"))))
                .andExpect(status().isOk()).andReturn());
        String runId = waiting.path("agentRunId").asText();
        String planId = waiting.path("taskPlanId").asText();
        String plansPath = "/api/v1/chat/conversations/" + waiting.path("conversationId").asText()
                + "/tasks/" + waiting.path("rootTaskId").asText() + "/plans/" + planId;
        mockMvc.perform(post(plansPath + "/approve").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post(plansPath + "/activate").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        JsonNode toolWait = data(mockMvc.perform(post("/api/v1/chat/runs/" + runId + "/resume-plan")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("taskPlanId", planId))))
                .andExpect(status().isOk()).andReturn());
        assertThat(toolWait.path("executionState").asText()).isEqualTo("WAITING_APPROVAL");
        String approvalId = toolWait.path("pendingApprovalId").asText();
        assertThat(runtimeApi.findLatestCheckpointByPhase(runId, "chat-approval-waiting"))
                .get().extracting(value -> value.stateSnapshot()).asString()
                .contains("planProgress", planId, "completedSteps");
        mockMvc.perform(post("/api/v1/chat/runs/" + runId + "/resume-plan")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("taskPlanId", planId))))
                .andExpect(status().isConflict());

        JsonNode completed = data(mockMvc.perform(post(
                                "/api/v1/chat/runs/" + runId + "/resume-approval")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("approvalId", approvalId))))
                .andExpect(status().isOk()).andReturn());
        assertThat(completed.path("executionState").asText()).isEqualTo("COMPLETED");
        assertThat(completed.path("taskPlanState").asText()).isEqualTo("COMPLETED");
        assertCompletedStepCheckpoint(runId, planId);
    }

    @Test
    void toolUnknownCheckpointRetainsPlanProgressAndContinuesAfterReconciliation() throws Exception {
        String username = "planned-unknown-" + UUID.randomUUID().toString().substring(0, 8)
                + "@example.com";
        JsonNode auth = data(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username, "password", "password123",
                                "displayName", "Planned Unknown"))))
                .andExpect(status().isOk()).andReturn());
        String token = auth.path("token").asText();
        String agentId = data(mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"planned-unknown-agent\"}"))
                .andExpect(status().isCreated()).andReturn()).path("id").asText();
        publishedAgentFixture.publish(agentId);
        JsonNode waiting = data(mockMvc.perform(post("/api/v1/chat/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "agentId", agentId, "message", "execute planned unknown"))))
                .andExpect(status().isOk()).andReturn());
        String runId = waiting.path("agentRunId").asText();
        String planId = waiting.path("taskPlanId").asText();
        String planPath = "/api/v1/chat/conversations/" + waiting.path("conversationId").asText()
                + "/tasks/" + waiting.path("rootTaskId").asText() + "/plans/" + planId;
        mockMvc.perform(post(planPath + "/approve").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post(planPath + "/activate").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        JsonNode unknown = data(mockMvc.perform(post("/api/v1/chat/runs/" + runId + "/resume-plan")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("taskPlanId", planId))))
                .andExpect(status().isOk()).andReturn());
        assertThat(unknown.path("executionState").asText())
                .isEqualTo("WAITING_RECONCILIATION");
        String toolCallId = unknown.path("pendingToolCallId").asText();
        assertThat(runtimeApi.findLatestCheckpointByPhase(runId, "chat-tool-unknown"))
                .get().extracting(value -> value.stateSnapshot()).asString()
                .contains("planProgress", planId, "completedSteps");

        JsonNode completed = data(mockMvc.perform(post(
                                "/api/v1/chat/runs/" + runId + "/tools/" + toolCallId + "/reconcile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "expectedRevision", unknown.path("pendingToolRevision").asLong(),
                                "reason", "verified fixture postcondition"))))
                .andExpect(status().isOk()).andReturn());
        assertThat(completed.path("executionState").asText()).isEqualTo("COMPLETED");
        assertThat(completed.path("taskPlanState").asText()).isEqualTo("COMPLETED");
        assertCompletedStepCheckpoint(runId, planId);
    }

    private void assertCompletedStepCheckpoint(String runId, String planId) {
        assertThat(runtimeApi.findLatestCheckpointByPhase(runId, "chat-plan-step-completed"))
                .get().extracting(value -> value.stateSnapshot()).asString()
                .contains(planId, "completedSteps", "tool", "finish", "modelSelection");
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    @TestConfiguration
    static class Configuration {
        @Bean @Primary
        MultiAgentOrchestrationPort planner() {
            return request -> new MultiAgentOrchestrationResponse(
                    "multi-agent/v1", request.requestId(), request.agentRunId(),
                    new MultiAgentOrchestrationResponse.Command(
                            MultiAgentOrchestrationResponse.CommandKind.PLAN_PROPOSED,
                            Map.of("rootTaskId", request.taskId(), "strategySummary", "tool then finish",
                                    "steps", List.of(
                                            Map.of("stepKey", "tool", "goal", "use tool", "dependsOnStepKeys", List.of()),
                                            Map.of("stepKey", "finish", "goal", "finish", "dependsOnStepKeys", List.of("tool"))))),
                    new MultiAgentOrchestrationResponse.Orchestration("planner", true));
        }

        @Bean @Primary
        InferenceExecutionApi inference() {
            Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
            return command -> switch (calls.computeIfAbsent(
                    command.agentRunId(), ignored -> new AtomicInteger()).getAndIncrement()) {
                case 0 -> new InferenceExecutionApi.InferenceExecutionResult(
                        "", 10, 5, List.of(new InferenceExecutionApi.InferenceToolCall(
                                "planned-tool-" + command.agentRunId(), "planned_tool",
                                command.messages().stream().anyMatch(message ->
                                        message.content().contains("planned unknown"))
                                        ? "{\"mode\":\"unknown\"}" : "{}")));
                case 1 -> new InferenceExecutionApi.InferenceExecutionResult("tool step done", 8, 4);
                case 2 -> new InferenceExecutionApi.InferenceExecutionResult("finish step done", 8, 4);
                default -> new InferenceExecutionApi.InferenceExecutionResult("final planned answer", 8, 4);
            };
        }

        @Bean @Primary
        RuntimeToolExecutionApplicationApi tooling() {
            Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
            return command -> {
                int attempt = calls.computeIfAbsent(
                        command.toolCallId(), ignored -> new AtomicInteger()).getAndIncrement();
                if (attempt == 0 && command.argumentsJson().contains("unknown")) {
                    return new RuntimeToolExecutionApplicationApi.RuntimeToolResult(
                            command.toolCallId(), command.toolId(), "UNKNOWN",
                            null, null, "ambiguous", 7L);
                }
                if (attempt == 0) {
                    throw new GovernanceApprovalRequiredException("planned-approval");
                }
                return new RuntimeToolExecutionApplicationApi.RuntimeToolResult(
                        command.toolCallId(), command.toolId(), "SUCCEEDED", "tool evidence", null, null);
            };
        }

        @Bean @Primary
        RuntimeToolReconciliationApplicationApi reconciliation() {
            return command -> new RuntimeToolReconciliationApplicationApi.ReconciliationView(
                    command.agentRunId(), command.toolCallId(), "planned_tool",
                    "RECONCILED", "SUCCEEDED", command.expectedRevision() + 1,
                    "fixture", Map.of("verified", "true"), Instant.now());
        }
    }
}
