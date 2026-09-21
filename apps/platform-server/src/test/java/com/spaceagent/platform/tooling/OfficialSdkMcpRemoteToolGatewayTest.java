package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.domain.McpAuthType;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpConnectionState;
import com.spaceagent.platform.tooling.infrastructure.OfficialSdkMcpRemoteToolGateway;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OfficialSdkMcpRemoteToolGatewayTest {
    @TempDir Path temporaryDirectory;

    @Test
    void officialSdkProbesListsAndCallsStreamableHttpTool() throws Exception {
        try (Fixture fixture = fixture("normal")) {
            var gateway = fixture.gateway();
            var connection = fixture.connection();

            var probe = gateway.probe(connection, Map.of("access_token", "sdk-secret"));
            assertEquals("2025-06-18", probe.protocolVersion());
            assertEquals("spaceagent-http-echo", probe.serverName());
            assertEquals("1.0.0", probe.serverVersion());
            assertEquals("echo", probe.tools().getFirst().name());
            assertFalse(probe.tools().getFirst().readOnly());
            assertEquals("echo", gateway.listTools(
                    connection, Map.of("access_token", "sdk-secret")).getFirst().name());

            var result = gateway.callTool(
                    connection, Map.of("access_token", "sdk-secret"),
                    "echo", Map.of("message", "hello"));
            assertFalse(result.error());
            assertTrue(result.text().contains("hello"));
            var resource=gateway.listResources(connection,Map.of("access_token","sdk-secret")).getFirst();
            assertEquals("resource://fixture/readme",resource.uri());
            var content=gateway.readResource(connection,Map.of("access_token","sdk-secret"),resource.uri());
            assertEquals("fixture readme",content.text());
            assertFalse(content.blob());
            var prompt=gateway.listPrompts(connection,Map.of("access_token","sdk-secret")).getFirst();
            assertEquals("summarize",prompt.name());
            assertTrue(prompt.arguments().getFirst().required());
            var rendered=gateway.getPrompt(connection,Map.of("access_token","sdk-secret"),prompt.name(),Map.of("topic","bounded MCP"));
            assertEquals("USER",rendered.messages().getFirst().role());
            assertEquals("Summarize bounded MCP",rendered.messages().getFirst().text());
            assertTrue(rendered.messages().getFirst().textContent());
            assertNoTasks(fixture);
        }
    }

    @Test
    void rejectsAdvertisedTasksWithoutDeclaringOrSendingTaskMethods() throws Exception {
        try (Fixture fixture = fixture("advertised", "--advertise-tasks")) {
            BusinessException error = assertThrows(BusinessException.class, () ->
                    fixture.gateway().probe(
                            fixture.connection(), Map.of("access_token", "sdk-secret")));
            assertEquals("MCP_TASKS_UNSUPPORTED", error.getCode());
            assertNoTasks(fixture);
        }
    }

    @Test
    void rejectsRemoteTaskResultWithoutPollingUpdatingOrCancelling() throws Exception {
        try (Fixture fixture = fixture("result", "--task-result")) {
            BusinessException error = assertThrows(BusinessException.class, () ->
                    fixture.gateway().callTool(
                            fixture.connection(), Map.of("access_token", "sdk-secret"),
                            "echo", Map.of("message", "hello")));
            assertEquals("MCP_TASKS_UNSUPPORTED", error.getCode());
            assertNoTasks(fixture);
        }
    }

    private Fixture fixture(String name, String... options) throws Exception {
        Path portFile = temporaryDirectory.resolve(name + "-port");
        Path requestLog = temporaryDirectory.resolve(name + "-requests.jsonl");
        Path script = Path.of("scripts/fixtures/mcp-streamable-http-echo-server.py");
        if (!Files.exists(script)) {
            script = Path.of("../../scripts/fixtures/mcp-streamable-http-echo-server.py");
        }
        List<String> command = new ArrayList<>(List.of(
                "python3", script.toAbsolutePath().normalize().toString(),
                "--port-file", portFile.toString(), "--token", "sdk-secret",
                "--request-log", requestLog.toString()));
        command.addAll(Arrays.asList(options));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        for (int attempt = 0;
             attempt < 200 && (!Files.exists(portFile) || Files.size(portFile) == 0)
                     && process.isAlive();
             attempt++) {
            Thread.sleep(25);
        }
        if (!Files.exists(portFile) || Files.size(portFile) == 0) {
            throw new AssertionError(new String(process.getInputStream().readAllBytes()));
        }
        int port = Integer.parseInt(Files.readString(portFile));
        McpConnection connection = new McpConnection(
                "connection", "installation", "tenant", "user",
                "http://127.0.0.1:" + port + "/mcp", "", McpAuthType.BEARER,
                McpConnectionState.ACTIVE, null, null, 1,
                Instant.now(), Instant.now(), null);
        return new Fixture(
                process, requestLog, connection,
                new OfficialSdkMcpRemoteToolGateway(URI::create, new ObjectMapper()));
    }

    private static void assertNoTasks(Fixture fixture) throws Exception {
        String requests = Files.readString(fixture.requestLog());
        assertFalse(requests.contains("io.modelcontextprotocol/tasks"));
        assertFalse(requests.contains("\"method\": \"tasks/"));
        assertFalse(requests.contains("\"tasks\":"));
    }

    private record Fixture(
            Process process,
            Path requestLog,
            McpConnection connection,
            OfficialSdkMcpRemoteToolGateway gateway) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            process.destroyForcibly();
            process.waitFor();
        }
    }
}
