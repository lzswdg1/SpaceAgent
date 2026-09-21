package com.spaceagent.platform.contracts;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.runtime.domain.multiagent.MultiAgentOrchestrationRequest;
import com.spaceagent.platform.runtime.domain.multiagent.MultiAgentOrchestrationResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Java guard for the same multi-agent/v1 fixtures parsed by TypeScript Zod. */
class PlatformMultiAgentContractCompatibilityTest {

    private static final Path CONTRACT = Path.of("../../contracts/multi-agent/v1");
    private static final Path FIXTURES = CONTRACT.resolve("fixtures");

    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    void requestAndResponseFixturesMatchStrictJavaTransportModels() throws Exception {
        List<String> routes = List.of("plan", "delegate", "handoff", "review", "approval", "complete");
        List<String> commands = List.of(
                "PLAN_PROPOSED", "DELEGATE_SUBTASK", "HANDOFF_PROPOSED", "REVIEW_REQUIRED",
                "APPROVAL_REQUIRED", "COMPLETED");
        List<String> graphRoutes = List.of(
                "planner", "delegate", "handoff", "review", "approval", "complete");
        for (int index = 0; index < routes.size(); index++) {
            String route = routes.get(index);
            RequestFixture request = objectMapper.readValue(
                    readFixture("request-" + route + ".json"), RequestFixture.class);
            ResponseFixture response = objectMapper.readValue(
                    readFixture("response-" + route + ".json"), ResponseFixture.class);

            assertEquals("multi-agent/v1", request.contractVersion());
            assertEquals(request.requestId(), response.requestId());
            assertEquals(request.agentRunId(), response.agentRunId());
            assertEquals(commands.get(index), response.command().kind());
            assertEquals(graphRoutes.get(index), response.orchestration().route());
            assertTrue(response.orchestration().ephemeral());

            JsonNode raw = objectMapper.readTree(readFixture("request-" + route + ".json"));
            assertNull(raw.findValue("apiKey"));
            assertNull(raw.findValue("providerSecret"));
            assertNull(raw.findValue("baseUrl"));
        }
    }

    @Test
    void canonicalSchemasAreStrictFrameworkNeutralDocuments() throws Exception {
        for (String name : List.of(
                "orchestration-request.schema.json",
                "orchestration-response.schema.json")) {
            Path path = CONTRACT.resolve(name);
            assertTrue(Files.isRegularFile(path), "missing schema: " + path);
            JsonNode schema = objectMapper.readTree(Files.readString(path));
            assertEquals("object", schema.path("type").asText());
            assertFalse(schema.path("additionalProperties").asBoolean(true));
            assertNotNull(schema.path("properties"));
            String text = schema.toString();
            assertFalse(text.contains("LangGraph"));
            assertFalse(text.contains("apiKey"));
            assertFalse(text.contains("providerSecret"));
        }
    }

    @Test
    void providerReasoningBoundaryMatchesStrictJavaAndTypeScriptContract() throws Exception {
        MultiAgentOrchestrationRequest request = objectMapper.readValue(
                readFixture("request-provider-reasoning.json"),
                MultiAgentOrchestrationRequest.class);
        MultiAgentOrchestrationResponse response = objectMapper.readValue(
                readFixture("response-model.json"),
                MultiAgentOrchestrationResponse.class);

        assertEquals(MultiAgentOrchestrationRequest.ReasoningMode.PROVIDER,
                request.reasoning().mode());
        assertEquals(0, request.reasoning().round());
        assertNull(request.reasoning().result());
        assertEquals(MultiAgentOrchestrationResponse.CommandKind.MODEL_REQUESTED,
                response.command().kind());
        assertEquals("model", response.orchestration().route());
        String combined = Files.readString(FIXTURES.resolve("request-provider-reasoning.json"))
                + Files.readString(FIXTURES.resolve("response-model.json"));
        assertFalse(combined.contains("apiKey"));
        assertFalse(combined.contains("providerSecret"));
        assertFalse(combined.contains("baseUrl"));
    }

    @Test
    void planProposalRejectsUnknownAndCyclicDependencies() {
        assertThrows(IllegalArgumentException.class,
                () -> new MultiAgentOrchestrationResponse.Command(
                        MultiAgentOrchestrationResponse.CommandKind.PLAN_PROPOSED,
                        Map.of(
                                "rootTaskId", "task-1",
                                "strategySummary", "invalid",
                                "steps", List.of(
                                        Map.of("stepKey", "a", "goal", "A",
                                                "dependsOnStepKeys", List.of("b")),
                                        Map.of("stepKey", "b", "goal", "B",
                                                "dependsOnStepKeys", List.of("a"))))));
        assertThrows(IllegalArgumentException.class,
                () -> new MultiAgentOrchestrationResponse.Command(
                        MultiAgentOrchestrationResponse.CommandKind.PLAN_PROPOSED,
                        Map.of(
                                "rootTaskId", "task-1",
                                "strategySummary", "invalid",
                                "steps", List.of(Map.of(
                                        "stepKey", "a", "goal", "A",
                                        "dependsOnStepKeys", List.of("missing"))))));
    }

    private String readFixture(String name) throws Exception {
        Path path = FIXTURES.resolve(name);
        assertTrue(Files.isRegularFile(path), "missing fixture: " + path);
        return Files.readString(path);
    }

    private record RequestFixture(
            String contractVersion,
            String requestId,
            String agentRunId,
            String organizationId,
            String userId,
            String mode,
            String projectId,
            String taskId,
            String taskPlanId,
            String conversationId,
            List<AgentRef> agentRefs,
            String modelPoolRef,
            ContextFixture context,
            CapabilitiesFixture capabilities,
            CursorFixture cursor,
            LimitsFixture limits) {
    }

    private record AgentRef(String agentId, String runConfigurationSnapshotId, String role) {
    }

    private record ContextFixture(
            String contextPackageId,
            int tokenBudget,
            List<ContextSourceFixture> sources) {
    }

    private record ContextSourceFixture(
            String type,
            String sourceId,
            String content,
            int priority) {
    }

    private record CapabilitiesFixture(
            List<String> allowedToolNames,
            List<String> allowedSkillIds,
            List<String> allowedMcpServerIds) {
    }

    private record CursorFixture(
            String phase,
            String checkpointId,
            String planStepId,
            String childTaskId) {
    }

    private record LimitsFixture(
            int maxDelegations,
            int maxParallelAgents,
            int maxDepth,
            int remainingTokenBudget) {
    }

    private record ResponseFixture(
            String contractVersion,
            String requestId,
            String agentRunId,
            CommandFixture command,
            OrchestrationFixture orchestration) {
    }

    private record CommandFixture(String kind, JsonNode payload) {
    }

    private record OrchestrationFixture(String route, boolean ephemeral) {
    }
}
