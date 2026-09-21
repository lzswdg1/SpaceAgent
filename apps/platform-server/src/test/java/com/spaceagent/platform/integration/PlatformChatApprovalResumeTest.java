package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.inference.api.ModelCallLedgerApplicationApi;
import com.spaceagent.platform.inference.domain.InferenceExecutor;
import com.spaceagent.platform.inference.domain.ModelProviderConnectionTester;
import com.spaceagent.platform.inference.domain.ProviderConnectionProbeResult;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeCoordinationApplicationApi;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.project.api.GetChatTaskQuery;
import com.spaceagent.platform.project.api.TaskApplicationApi;
import com.spaceagent.platform.project.domain.TaskState;
import com.spaceagent.platform.tooling.api.ExternalRuntimeToolApplicationApi;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi;
import com.spaceagent.platform.tooling.domain.ToolExecutionStatus;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PlatformChatApprovalResumeTest.FixtureConfiguration.class)
class PlatformChatApprovalResumeTest {
    @Autowired PublishedAgentFixture publishedAgentFixture;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;
    @Autowired RuntimeApplicationApi runtime;
    @Autowired RuntimeCoordinationApplicationApi coordination;
    @Autowired ToolExecutionLedgerApplicationApi toolLedger;
    @Autowired ModelCallLedgerApplicationApi modelLedger;
    @Autowired TaskApplicationApi taskApi;
    @Autowired FixtureExternalTools externalTools;

    @Test
    void pausesWithExactCheckpointAndResumesSameRunOnceAfterApproval() throws Exception {
        externalTools.reset();
        Identity identity = register("chat-approval@example.com");
        enableNetworkApproval(identity);
        String providerId = createProvider(identity);
        String agentId = createAgent(identity, providerId);
        publishedAgentFixture.publish(agentId);

        JsonNode waiting = data(mockMvc.perform(post("/api/v1/chat/messages")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "agentId", agentId,
                                "message", "approval-flow"))))
                .andExpect(status().isOk()).andReturn());
        String runId = waiting.path("agentRunId").asText();
        String conversationId = waiting.path("conversationId").asText();
        String approvalId = waiting.path("pendingApprovalId").asText();
        String rootTaskId = waiting.path("rootTaskId").asText();

        assertThat(waiting.path("executionState").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(waiting.path("pendingToolName").asText()).isEqualTo("http_fetch");
        assertThat(approvalId).isNotBlank();
        assertThat(rootTaskId).isNotBlank();
        assertThat(waiting.path("rootTaskState").asText()).isEqualTo("IN_PROGRESS");
        assertThat(runtime.findRun(runId).orElseThrow().state())
                .isEqualTo(AgentRunState.WAITING_FOR_USER);
        assertThat(runtime.findLatestCheckpoint(runId).orElseThrow().stateSnapshot())
                .contains("chat-approval-waiting", "chat-approval/v1", approvalId,
                        "assistantReservationId", "modelSelection");
        assertThat(data(mockMvc.perform(get(
                                "/api/v1/chat/conversations/" + conversationId + "/messages")
                        .header("Authorization", bearer(identity.token())))
                .andExpect(status().isOk()).andReturn()).size()).isEqualTo(1);
        JsonNode restored=data(mockMvc.perform(get("/api/v1/chat/conversations/"+conversationId+"/run")
                .header("Authorization",bearer(identity.token()))).andExpect(status().isOk()).andReturn());
        assertThat(restored.path("executionState").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(restored.path("approvalId").asText()).isEqualTo(approvalId);
        assertThat(restored.toString()).doesNotContain("toolResults","initialContent","modelSelection");
        assertThat(externalTools.calls()).isZero();

        mockMvc.perform(post("/api/v1/chat/runs/" + runId + "/resume-approval")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "approvalId", UUID.randomUUID().toString()))))
                .andExpect(status().isConflict());
        assertThat(externalTools.calls()).isZero();

        JsonNode stillWaiting = data(mockMvc.perform(post(
                                "/api/v1/chat/runs/" + runId + "/resume-approval")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("approvalId", approvalId))))
                .andExpect(status().isOk()).andReturn());
        assertThat(stillWaiting.path("executionState").asText())
                .isEqualTo("WAITING_APPROVAL");
        assertThat(runtime.findRun(runId).orElseThrow().state())
                .isEqualTo(AgentRunState.WAITING_FOR_USER);
        assertThat(externalTools.calls()).isZero();

        var held = coordination.acquireLease(
                new RuntimeCoordinationApplicationApi.AcquireLeaseCommand(
                        runId, "test-chat-busy", 30));
        mockMvc.perform(post("/api/v1/chat/runs/" + runId + "/resume-approval")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("approvalId", approvalId))))
                .andExpect(status().isConflict());
        coordination.releaseLease(new RuntimeCoordinationApplicationApi.ReleaseLeaseCommand(
                runId, "test-chat-busy", held.lease().leaseToken(),
                held.lease().fencingToken()));
        assertThat(externalTools.calls()).isZero();

        mockMvc.perform(post("/api/v1/governance/approvals/" + approvalId + "/decision")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\",\"note\":\"verified\"}"))
                .andExpect(status().isOk());

        JsonNode completed = data(mockMvc.perform(post(
                                "/api/v1/chat/runs/" + runId + "/resume-approval")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("approvalId", approvalId))))
                .andExpect(status().isOk()).andReturn());

        assertThat(completed.path("agentRunId").asText()).isEqualTo(runId);
        assertThat(completed.path("conversationId").asText()).isEqualTo(conversationId);
        assertThat(completed.path("rootTaskId").asText()).isEqualTo(rootTaskId);
        assertThat(completed.path("rootTaskState").asText()).isEqualTo("COMPLETED");
        assertThat(completed.path("executionState").asText()).isEqualTo("COMPLETED");
        assertThat(completed.path("assistantMessage").asText())
                .isEqualTo("approved tool synthesis");
        assertThat(runtime.findRun(runId).orElseThrow().state())
                .isEqualTo(AgentRunState.COMPLETED);
        assertThat(externalTools.calls()).isEqualTo(1);
        assertThat(toolLedger.findByRunId(runId)).singleElement()
                .satisfies(value -> assertThat(value.status())
                        .isEqualTo(ToolExecutionStatus.SUCCEEDED));
        assertThat(modelLedger.findByRunId(runId)).hasSize(2);
        JsonNode approval = data(mockMvc.perform(get(
                                "/api/v1/governance/approvals/" + approvalId)
                        .header("Authorization", bearer(identity.token())))
                .andExpect(status().isOk()).andReturn());
        assertThat(approval.path("state").asText()).isEqualTo("CONSUMED");

        mockMvc.perform(post("/api/v1/chat/runs/" + runId + "/resume-approval")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("approvalId", approvalId))))
                .andExpect(status().isConflict());
        assertThat(externalTools.calls()).isEqualTo(1);
    }

    @Test
    void streamEndsWithSuspendedEventInsteadOfFalseCompletion() throws Exception {
        externalTools.reset();
        Identity identity = register("chat-approval-stream@example.com");
        enableNetworkApproval(identity);
        String providerId = createProvider(identity);
        String agentId = createAgent(identity, providerId);
        publishedAgentFixture.publish(agentId);

        MvcResult pending = mockMvc.perform(post("/api/v1/chat/messages/stream")
                        .header("Authorization", bearer(identity.token()))
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "agentId", agentId,
                                "message", "approval-flow"))))
                .andExpect(request().asyncStarted()).andReturn();
        String stream = mockMvc.perform(asyncDispatch(pending))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(stream).contains("event:approval_required", "event:suspended",
                "WAITING_APPROVAL", "http_fetch");
        assertThat(stream).doesNotContain("event:done");
        assertThat(externalTools.calls()).isZero();
    }

    @Test
    void rejectedApprovalFailsClosedWithoutExecutingTheTool() throws Exception {
        externalTools.reset();
        Identity identity = register("chat-approval-rejected@example.com");
        enableNetworkApproval(identity);
        String agentId = createAgent(identity, createProvider(identity));
        publishedAgentFixture.publish(agentId);
        JsonNode waiting = data(mockMvc.perform(post("/api/v1/chat/messages")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "agentId", agentId, "message", "approval-flow"))))
                .andExpect(status().isOk()).andReturn());
        String runId = waiting.path("agentRunId").asText();
        String approvalId = waiting.path("pendingApprovalId").asText();
        String rootTaskId = waiting.path("rootTaskId").asText();
        mockMvc.perform(post("/api/v1/governance/approvals/" + approvalId + "/decision")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECTED\",\"note\":\"deny\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/chat/runs/" + runId + "/resume-approval")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("approvalId", approvalId))))
                .andExpect(status().isConflict());

        assertThat(runtime.findRun(runId).orElseThrow().state()).isEqualTo(AgentRunState.FAILED);
        assertThat(taskApi.getChatTask(new GetChatTaskQuery(
                identity.tenantId(), identity.userId(),
                waiting.path("conversationId").asText(), rootTaskId)).state())
                .isEqualTo(TaskState.FAILED);
        assertThat(externalTools.calls()).isZero();
        assertThat(toolLedger.findByRunId(runId)).isEmpty();
    }

    private void enableNetworkApproval(Identity identity) throws Exception {
        mockMvc.perform(put("/api/v1/governance/policy")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requireCodingFileApproval":false,
                                 "requireCommandApproval":false,
                                 "requireAutomationApproval":false,
                                 "requireNetworkApproval":true,
                                 "requireSourceMergeApproval":false,
                                 "separationOfDuties":false,
                                 "approvalTtlSeconds":300,
                                 "expectedRevision":0}
                                """))
                .andExpect(status().isOk());
    }

    private String createProvider(Identity identity) throws Exception {
        return data(mockMvc.perform(post("/api/v1/model-providers")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "approval-provider",
                                "type", "openai",
                                "baseUrl", "https://example.com/v1",
                                "apiKey", "provider-fixture",
                                "models", List.of(Map.of(
                                        "modelId", "approval-model",
                                        "displayName", "approval-model",
                                        "maxContextTokens", 32768,
                                        "isDefault", true))))))
                .andExpect(status().isCreated()).andReturn()).path("id").asText();
    }

    private String createAgent(Identity identity, String providerId) throws Exception {
        return data(mockMvc.perform(post("/api/v1/agents")
                        .header("Authorization", bearer(identity.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "name", "approval-agent",
                                "systemPrompt", "Use approved evidence",
                                "modelProviderId", providerId,
                                "modelId", "approval-model",
                                "enabledToolIds", List.of("http_fetch"),
                                "networkEnabled", true))))
                .andExpect(status().isCreated()).andReturn()).path("id").asText();
    }

    private Identity register(String username) throws Exception {
        JsonNode auth = data(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "username", username,
                                "password", "password123",
                                "displayName", username))))
                .andExpect(status().isOk()).andReturn());
        return new Identity(auth.path("userId").asText(), auth.path("tenantId").asText(),
                auth.path("token").asText());
    }

    private JsonNode data(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record Identity(String userId, String tenantId, String token) {}

    static final class FixtureExternalTools implements ExternalRuntimeToolApplicationApi {
        private final AtomicInteger calls = new AtomicInteger();

        int calls() { return calls.get(); }
        void reset() { calls.set(0); }

        @Override
        public ToolResult execute(ExecuteCommand command) {
            calls.incrementAndGet();
            return new ToolResult("{\"content\":\"approved evidence\"}", true, false);
        }

        @Override
        public McpToolDescriptor describeMcpTool(
                String tenantId, String userId, String connectionId, String remoteTool) {
            throw new UnsupportedOperationException();
        }
    }

    @TestConfiguration
    static class FixtureConfiguration {
        @Bean @Primary
        ModelProviderConnectionTester connectionTester() {
            return provider -> new ProviderConnectionProbeResult(
                    true, 5, List.of("approval-model"), null);
        }

        @Bean @Primary
        FixtureExternalTools externalTools() {
            return new FixtureExternalTools();
        }

        @Bean @Primary
        InferenceExecutor approvalInferenceExecutor() {
            return request -> {
                boolean synthesis = request.messages().stream()
                        .anyMatch(message -> message.content().contains(
                                "The authorized Tools have completed"));
                int tokens = Math.max(1, request.messages().stream()
                        .mapToInt(message -> message.content().length()).sum() / 4);
                if (synthesis) {
                    return new InferenceExecutor.InferenceExecution(
                            "approved tool synthesis", tokens, 6);
                }
                return new InferenceExecutor.InferenceExecution(
                        "", tokens, 0,
                        List.of(new InferenceExecutor.InferenceToolCall(
                                "call-approval", "http_fetch",
                                "{\"url\":\"https://example.com/evidence\"}")));
            };
        }
    }
}
