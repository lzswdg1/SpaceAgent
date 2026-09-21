package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.inference.api.InferenceExecutionApi;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.domain.MultiAgentOrchestrationPort;
import com.spaceagent.platform.runtime.domain.multiagent.MultiAgentOrchestrationRequest;
import com.spaceagent.platform.runtime.domain.multiagent.MultiAgentOrchestrationResponse;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "platform.chat.automatic-planning-enabled=true")
@AutoConfigureMockMvc
@Import(PlatformSkillRuntimeIntegrationTest.Configuration.class)
class PlatformSkillRuntimeIntegrationTest {
    @Autowired PublishedAgentFixture publishedAgentFixture;
    private static final String INSTRUCTIONS = "Always cite the inspected evidence marker.";

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired RuntimeApplicationApi runtimeApi;

    @BeforeEach
    void resetCaptures() {
        Configuration.PLANNER.set(null);
        Configuration.INFERENCE.set(null);
    }

    @Test
    void injectsPinnedSkillIntoPlannerAndChatWhileCheckpointStaysContentFree() throws Exception {
        Setup setup = provision("skill-runtime-agent");
        String token = setup.token();
        String skillId = setup.skillId();
        String versionId = setup.versionId();
        String hash = setup.hash();
        String agentId = setup.agentId();

        JsonNode completed = chat(token, agentId, "use the pinned skill");
        String runId = completed.path("agentRunId").asText();
        MultiAgentOrchestrationRequest planner = Configuration.PLANNER.get();
        assertThat(planner.context().sources())
                .anySatisfy(source -> {
                    assertThat(source.type()).isEqualTo("SKILL");
                    assertThat(source.sourceId()).isEqualTo(versionId);
                    assertThat(source.content()).contains(INSTRUCTIONS);
                });
        assertThat(Configuration.INFERENCE.get().messages())
                .anySatisfy(message -> assertThat(message.content()).contains(INSTRUCTIONS));
        String evidence = runtimeApi.findLatestCheckpointByPhase(runId, "skill-context-bound")
                .orElseThrow().stateSnapshot();
        assertThat(evidence).contains(versionId, hash, "skill-context-bound")
                .doesNotContain(INSTRUCTIONS, "Evidence marker");

        String path = "/api/v1/chat/runs/" + runId + "/skill-evidence";
        String response = mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(response).contains("CONTEXT_PREPARED", versionId, hash).doesNotContain(INSTRUCTIONS, "Evidence marker", "stateSnapshot");
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path).header("Authorization", "Bearer " + register())).andExpect(status().isNotFound());
        var original = runtimeApi.findRun(runId).orElseThrow();
        var badRun = runtimeApi.startRun(new com.spaceagent.platform.runtime.api.StartAgentRunCommand(
                original.tenantId(), original.ownerId(), agentId, null, original.conversationId(),
                null, null, null, null, null, null, null, null));
        String badPath = "/api/v1/chat/runs/" + badRun.id() + "/skill-evidence";
        assertThat(mockMvc.perform(get(badPath).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).contains("NO_EVIDENCE");
        runtimeApi.markRunInProgress(badRun.id());
        runtimeApi.createCheckpoint(new com.spaceagent.platform.runtime.api.CreateCheckpointCommand(badRun.id(),
                "{\"phase\":\"skill-context-bound\",\"skills\":[{\"skillVersionId\":\"invalid-secret-value\",\"configHash\":\"bad\"}]}"));
        assertThat(mockMvc.perform(get(badPath).header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable()).andReturn().getResponse().getContentAsString())
                .contains("SKILL_EVIDENCE_INVALID").doesNotContain("invalid-secret-value");

        mockMvc.perform(post("/api/v1/skills/" + skillId + "/versions/" + versionId + "/deprecate")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/skills/" + skillId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        assertThat(chat(token, agentId, "resume with historical pinned skill")
                .path("executionState").asText()).isEqualTo("COMPLETED");
        assertThat(Configuration.INFERENCE.get().messages())
                .anySatisfy(message -> assertThat(message.content()).contains(INSTRUCTIONS));
    }

    @Test
    void injectsPinnedSkillIntoEveryReviewedPlanStepAndSynthesis() throws Exception {
        Setup setup = provision("planned-skill-agent");
        JsonNode waiting = chat(setup.token(), setup.agentId(), "planned skill flow");
        assertThat(waiting.path("executionState").asText()).isEqualTo("WAITING_PLAN_APPROVAL");
        String planId = waiting.path("taskPlanId").asText();
        String path = "/api/v1/chat/conversations/" + waiting.path("conversationId").asText()
                + "/tasks/" + waiting.path("rootTaskId").asText() + "/plans/" + planId;
        mockMvc.perform(post(path + "/approve")
                        .header("Authorization", "Bearer " + setup.token()))
                .andExpect(status().isOk());
        mockMvc.perform(post(path + "/activate")
                        .header("Authorization", "Bearer " + setup.token()))
                .andExpect(status().isOk());
        JsonNode completed = data(mockMvc.perform(post(
                                "/api/v1/chat/runs/" + waiting.path("agentRunId").asText()
                                        + "/resume-plan")
                        .header("Authorization", "Bearer " + setup.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("taskPlanId", planId))))
                .andExpect(status().isOk()).andReturn());
        assertThat(completed.path("executionState").asText()).isEqualTo("COMPLETED");
        assertThat(Configuration.INFERENCE.get().messages())
                .anySatisfy(message -> assertThat(message.content()).contains(INSTRUCTIONS));
        assertThat(runtimeApi.findLatestCheckpointByPhase(
                        waiting.path("agentRunId").asText(), "skill-context-bound"))
                .get().extracting(value -> value.stateSnapshot()).asString()
                .contains(setup.versionId(), setup.hash())
                .doesNotContain(INSTRUCTIONS);
    }

    @Test
    void rejectsLongPlannerSkillsInsteadOfSilentlyTruncatingInstructions() throws Exception {
        Setup setup = provision("long-skill-agent", "x".repeat(33_000) + "must retain this final instruction");
        var result = mockMvc.perform(post("/api/v1/chat/messages")
                        .header("Authorization", "Bearer " + setup.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("agentId", setup.agentId(), "message", "plan this"))))
                .andExpect(status().isPayloadTooLarge()).andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("AGENT_SKILL_PLANNER_CONTEXT_TOO_LARGE");
        assertThat(Configuration.PLANNER.get()).isNull();
        assertThat(Configuration.INFERENCE.get()).isNull();
    }

    private Setup provision(String agentName) throws Exception {
        return provision(agentName, INSTRUCTIONS);
    }

    private Setup provision(String agentName, String instructions) throws Exception {
        String token = register();
        JsonNode skill = data(mockMvc.perform(post("/api/v1/skills")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Evidence marker",
                                "instructions", instructions,
                                "requiredToolIds", List.of("echo")))))
                .andExpect(status().isCreated()).andReturn());
        String skillId = skill.path("id").asText();
        String versionId = skill.path("versions").get(0).path("id").asText();
        String hash = skill.path("versions").get(0).path("configHash").asText();
        mockMvc.perform(post("/api/v1/skills/" + skillId + "/versions/" + versionId + "/publish")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        String agentId = data(mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", agentName,
                                "enabledToolIds", List.of("echo"),
                                "skillIds", List.of(versionId)))))
                .andExpect(status().isCreated()).andReturn()).path("id").asText();
        publishedAgentFixture.publish(agentId);
        return new Setup(token, skillId, versionId, hash, agentId);
    }

    private JsonNode chat(String token, String agentId, String message) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/chat/messages")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "agentId", agentId, "message", message))))
                .andReturn();
        if (result.getResponse().getStatus() != 200) {
            throw new AssertionError(
                    "Chat failed with status " + result.getResponse().getStatus(),
                    result.getResolvedException());
        }
        return data(result);
    }

    private String register() throws Exception {
        return data(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "skill-runtime-"
                                        + UUID.randomUUID().toString().substring(0, 8)
                                        + "@example.com",
                                "password", "password123",
                                "displayName", "Skill Runtime"))))
                .andExpect(status().isOk()).andReturn()).path("token").asText();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    @TestConfiguration
    static class Configuration {
        static final AtomicReference<MultiAgentOrchestrationRequest> PLANNER =
                new AtomicReference<>();
        static final AtomicReference<InferenceExecutionApi.InferenceExecutionCommand> INFERENCE =
                new AtomicReference<>();

        @Bean
        @Primary
        MultiAgentOrchestrationPort planner() {
            return request -> {
                PLANNER.set(request);
                boolean planned = request.context().sources().stream()
                        .anyMatch(source -> source.content().contains("planned skill flow"));
                return new MultiAgentOrchestrationResponse(
                        "multi-agent/v1", request.requestId(), request.agentRunId(),
                        new MultiAgentOrchestrationResponse.Command(
                                planned
                                        ? MultiAgentOrchestrationResponse.CommandKind.PLAN_PROPOSED
                                        : MultiAgentOrchestrationResponse.CommandKind.COMPLETED,
                                planned ? Map.of(
                                        "rootTaskId", request.taskId(),
                                        "strategySummary", "Skill-aware plan",
                                        "steps", List.of(
                                                Map.of("stepKey", "inspect", "goal", "Inspect",
                                                        "dependsOnStepKeys", List.of()),
                                                Map.of("stepKey", "answer", "goal", "Answer",
                                                        "dependsOnStepKeys", List.of("inspect"))))
                                        : Map.of("summary", "planning not required")),
                        new MultiAgentOrchestrationResponse.Orchestration(
                                planned ? "planner" : "complete", true));
            };
        }

        @Bean
        @Primary
        InferenceExecutionApi inference() {
            return command -> {
                INFERENCE.set(command);
                return new InferenceExecutionApi.InferenceExecutionResult(
                        "skill-aware answer", 8, 4);
            };
        }
    }

    private record Setup(
            String token, String skillId, String versionId, String hash, String agentId) { }
}
