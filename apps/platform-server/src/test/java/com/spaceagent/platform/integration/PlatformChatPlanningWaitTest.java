package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeCoordinationApplicationApi;
import com.spaceagent.platform.inference.api.ModelCallLedgerApplicationApi;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi;
import com.spaceagent.platform.runtime.domain.AgentRunState;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "platform.chat.automatic-planning-enabled=true")
@AutoConfigureMockMvc
@Import(PlatformChatPlanningWaitTest.PlannerConfiguration.class)
class PlatformChatPlanningWaitTest {
    @Autowired PublishedAgentFixture publishedAgentFixture;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RuntimeApplicationApi runtimeApi;
    @Autowired RuntimeCoordinationApplicationApi coordinationApi;
    @Autowired ModelCallLedgerApplicationApi modelCallLedgerApi;
    @Autowired ToolExecutionLedgerApplicationApi toolExecutionLedgerApi;

    @Test
    void automaticPlannerPersistsPlanAndSuspendsBeforeInferenceOrToolEffects() throws Exception {
        Identity identity = register("plan-wait-" + suffix() + "@example.com");
        String agentId = createAgent(identity);
        publishedAgentFixture.publish(agentId);

        JsonNode result = data(mockMvc.perform(post("/api/v1/chat/messages")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "agentId", agentId,
                                "message", "Research and then summarize the evidence"))))
                .andExpect(status().isOk()).andReturn());

        assertThat(result.path("executionState").asText()).isEqualTo("WAITING_PLAN_APPROVAL");
        assertThat(result.path("taskPlanState").asText()).isEqualTo("PROPOSED");
        assertThat(result.path("taskPlanId").asText()).isNotBlank();
        assertThat(result.path("rootTaskState").asText()).isEqualTo("IN_PROGRESS");
        assertThat(result.path("assistantMessage").asText()).isEmpty();
        String runId = result.path("agentRunId").asText();
        assertThat(runtimeApi.findRun(runId).orElseThrow().state())
                .isEqualTo(AgentRunState.WAITING_FOR_USER);
        assertThat(modelCallLedgerApi.findByRunId(runId)).isEmpty();
        assertThat(toolExecutionLedgerApi.findByRunId(runId)).isEmpty();
        assertThat(runtimeApi.findLatestCheckpointByPhase(runId, "chat-plan-review"))
                .get().extracting(value -> value.stateSnapshot())
                .asString().contains("chat-plan-review/v1", result.path("taskPlanId").asText())
                .doesNotContain("apiKey", "secret");

        String plansPath = "/api/v1/chat/conversations/" + result.path("conversationId").asText()
                + "/tasks/" + result.path("rootTaskId").asText() + "/plans";
        JsonNode plans = data(mockMvc.perform(get(plansPath)
                        .header("Authorization", bearer(identity.token())))
                .andExpect(status().isOk()).andReturn());
        assertThat(plans).hasSize(1);
        assertThat(plans.get(0).path("steps")).hasSize(2);
        assertThat(plans.get(0).path("steps").get(1).path("dependencyStepIds")).hasSize(1);

        String planId = result.path("taskPlanId").asText();
        mockMvc.perform(post("/api/v1/chat/runs/" + runId + "/resume-plan")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("taskPlanId", planId))))
                .andExpect(status().isConflict());
        mockMvc.perform(post(plansPath + "/" + planId + "/approve")
                        .header("Authorization", bearer(identity.token())))
                .andExpect(status().isOk());
        mockMvc.perform(post(plansPath + "/" + planId + "/activate")
                        .header("Authorization", bearer(identity.token())))
                .andExpect(status().isOk());
        var blocker = coordinationApi.acquireLease(
                new RuntimeCoordinationApplicationApi.AcquireLeaseCommand(
                        runId, "plan-resume-blocker", 30)).lease();
        mockMvc.perform(post("/api/v1/chat/runs/" + runId + "/resume-plan")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("taskPlanId", planId))))
                .andExpect(status().isConflict());
        coordinationApi.releaseLease(new RuntimeCoordinationApplicationApi.ReleaseLeaseCommand(
                runId, "plan-resume-blocker", blocker.leaseToken(), blocker.fencingToken()));
        JsonNode completed = data(mockMvc.perform(post(
                                "/api/v1/chat/runs/" + runId + "/resume-plan")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("taskPlanId", planId))))
                .andExpect(status().isOk()).andReturn());
        assertThat(completed.path("executionState").asText()).isEqualTo("COMPLETED");
        assertThat(completed.path("taskPlanState").asText()).isEqualTo("COMPLETED");
        assertThat(completed.path("rootTaskState").asText()).isEqualTo("COMPLETED");
        assertThat(runtimeApi.findRun(runId).orElseThrow().state())
                .isEqualTo(AgentRunState.COMPLETED);
        assertThat(modelCallLedgerApi.findByRunId(runId)).hasSize(3);
        assertThat(runtimeApi.findLatestCheckpointByPhase(
                        runId, "chat-plan-step-completed"))
                .get().extracting(value -> value.stateSnapshot()).asString()
                .contains("completedSteps", planId, "modelSelection");
        JsonNode completedPlan = data(mockMvc.perform(get(plansPath + "/" + planId)
                        .header("Authorization", bearer(identity.token())))
                .andExpect(status().isOk()).andReturn());
        assertThat(completedPlan.path("steps")).allSatisfy(step ->
                assertThat(step.path("state").asText()).isEqualTo("COMPLETED"));
    }

    @Test
    void streamingPlannerEndsWithSuspendedEventAndPlanReference() throws Exception {
        Identity identity = register("plan-stream-" + suffix() + "@example.com");
        String agentId = createAgent(identity);
        publishedAgentFixture.publish(agentId);
        MvcResult pending = mockMvc.perform(post("/api/v1/chat/messages/stream")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "agentId", agentId,
                                "message", "Plan this request before execution"))))
                .andExpect(request().asyncStarted()).andReturn();
        String stream = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(stream).contains(
                "event:planning_started", "event:plan_approval_required",
                "event:suspended", "WAITING_PLAN_APPROVAL", "taskPlanId", "PROPOSED");
        assertThat(stream).doesNotContain("event:done", "assistantMessage");
    }

    @Test
    void plannerCompletionContinuesTheCompatibleSingleRunChatPath() throws Exception {
        Identity identity = register("plan-skip-" + suffix() + "@example.com");
        String agentId = createAgent(identity);
        publishedAgentFixture.publish(agentId);
        JsonNode result = data(mockMvc.perform(post("/api/v1/chat/messages")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "agentId", agentId,
                                "message", "simple without plan"))))
                .andExpect(status().isOk()).andReturn());
        assertThat(result.path("executionState").asText()).isEqualTo("COMPLETED");
        assertThat(result.path("taskPlanId").isMissingNode()
                || result.path("taskPlanId").isNull()).isTrue();
        assertThat(result.path("assistantMessage").asText()).startsWith("noop-inference:");
        assertThat(runtimeApi.findRun(result.path("agentRunId").asText()).orElseThrow().state())
                .isEqualTo(AgentRunState.COMPLETED);
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

    private String createAgent(Identity identity) throws Exception {
        return data(mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"planning-agent\"}"))
                .andExpect(status().isCreated()).andReturn()).path("id").asText();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private record Identity(String userId, String tenantId, String token) { }

    @TestConfiguration
    static class PlannerConfiguration {
        @Bean
        @Primary
        MultiAgentOrchestrationPort chatPlanner() {
            return request -> {
                boolean skip = request.context().sources().stream()
                        .anyMatch(source -> source.content().contains("simple without plan"));
                if (skip) {
                    return new MultiAgentOrchestrationResponse(
                            "multi-agent/v1", request.requestId(), request.agentRunId(),
                            new MultiAgentOrchestrationResponse.Command(
                                    MultiAgentOrchestrationResponse.CommandKind.COMPLETED,
                                    Map.of("summary", "No explicit plan required")),
                            new MultiAgentOrchestrationResponse.Orchestration("complete", true));
                }
                return new MultiAgentOrchestrationResponse(
                    "multi-agent/v1", request.requestId(), request.agentRunId(),
                    new MultiAgentOrchestrationResponse.Command(
                            MultiAgentOrchestrationResponse.CommandKind.PLAN_PROPOSED,
                            Map.of(
                                    "rootTaskId", request.taskId(),
                                    "strategySummary", "Research, then summarize",
                                    "steps", List.of(
                                            Map.of("stepKey", "research", "goal", "Research evidence",
                                                    "dependsOnStepKeys", List.of()),
                                            Map.of("stepKey", "summarize", "goal", "Summarize evidence",
                                                    "dependsOnStepKeys", List.of("research"))))),
                    new MultiAgentOrchestrationResponse.Orchestration("planner", true));
            };
        }
    }
}
