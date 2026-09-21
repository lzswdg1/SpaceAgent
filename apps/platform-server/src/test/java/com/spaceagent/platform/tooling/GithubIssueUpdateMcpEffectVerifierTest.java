package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.application.GithubIssueUpdateMcpEffectVerifier;
import com.spaceagent.platform.tooling.application.McpConnectionAuthorizationService;
import com.spaceagent.platform.tooling.domain.LocalToolEffectVerifierRegistry;
import com.spaceagent.platform.tooling.domain.McpAuthType;
import com.spaceagent.platform.tooling.domain.McpCapabilitySnapshot;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpConnectionQualificationRepository;
import com.spaceagent.platform.tooling.domain.McpConnectionState;
import com.spaceagent.platform.tooling.domain.McpInstallation;
import com.spaceagent.platform.tooling.domain.McpInstallationScope;
import com.spaceagent.platform.tooling.domain.McpInstallationState;
import com.spaceagent.platform.tooling.domain.McpMarketplaceRepository;
import com.spaceagent.platform.tooling.domain.ToolEffectVerifier;
import com.spaceagent.platform.tooling.infrastructure.OfficialSdkMcpRemoteToolGateway;
import com.spaceagent.shared.exception.BusinessException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GithubIssueUpdateMcpEffectVerifierTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    @TempDir Path temporaryDirectory;

    @Test
    void provesExactIssueUpdateThroughReadOnlyOfficialSdkCall() throws Exception {
        try (Fixture fixture = fixture()) {
            ToolEffectVerifier.Evidence evidence = fixture.registry().verify(
                    request(fixture.connection(), fixture.snapshot(), "Verified title"));

            assertThat(evidence.verdict())
                    .isEqualTo(ToolEffectVerifier.Verdict.PROVEN_APPLIED);
            assertThat(evidence.safeCode()).isEqualTo("POSTCONDITION_MATCHED");
            assertThat(evidence.observationSha256()).startsWith("sha256:");
            String requests = Files.readString(fixture.requestLog());
            assertThat(requests).contains("\"name\": \"issue_read\"");
            assertThat(requests).doesNotContain("\"name\": \"issue_write\"");
        }
    }

    @Test
    void mismatchIsProofOnlyWhileStaleScopeAndCompositeWritesFailClosed() throws Exception {
        try (Fixture fixture = fixture()) {
            ToolEffectVerifier.Evidence mismatch = fixture.registry().verify(
                    request(fixture.connection(), fixture.snapshot(), "Different title"));
            assertThat(mismatch.verdict())
                    .isEqualTo(ToolEffectVerifier.Verdict.PROVEN_NOT_APPLIED);

            McpCapabilitySnapshot stale = new McpCapabilitySnapshot(
                    fixture.snapshot().id(), fixture.snapshot().connectionId(),
                    fixture.snapshot().connectionRevision(), fixture.snapshot().protocolVersion(),
                    fixture.snapshot().serverName(), null, fixture.snapshot().serverVersion(), null,
                    fixture.snapshot().capabilitiesJson(), fixture.snapshot().toolsJson(),
                    "f".repeat(64), NOW);
            assertThatThrownBy(() -> fixture.registry().verify(
                    request(fixture.connection(), stale, "Verified title")))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getCode())
                                    .isEqualTo("TOOL_EFFECT_VERIFIER_SCOPE_MISMATCH"));

            String composite = canonical("Verified title").replace(
                    "\"title\":\"Verified title\"",
                    "\"title\":\"Verified title\",\"labels\":[\"bug\"]");
            assertThatThrownBy(() -> fixture.registry().verify(request(
                    fixture.connection(), fixture.snapshot(), composite,
                    ToolEffectVerifier.sha256(composite))))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getCode())
                                    .isEqualTo("TOOL_EFFECT_VERIFIER_UNSUPPORTED"));
        }
    }

    private Fixture fixture() throws Exception {
        Path portFile = temporaryDirectory.resolve("port");
        Path requestLog = temporaryDirectory.resolve("requests.jsonl");
        Path script = Path.of("scripts/fixtures/mcp-streamable-http-echo-server.py");
        if (!Files.exists(script)) {
            script = Path.of("../../scripts/fixtures/mcp-streamable-http-echo-server.py");
        }
        List<String> command = new ArrayList<>(List.of(
                "python3", script.toAbsolutePath().normalize().toString(),
                "--port-file", portFile.toString(), "--token", "sdk-secret",
                "--request-log", requestLog.toString()));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        for (int attempt = 0;
             attempt < 200 && (!Files.exists(portFile) || Files.size(portFile) == 0)
                     && process.isAlive(); attempt++) {
            Thread.sleep(25);
        }
        if (!Files.exists(portFile) || Files.size(portFile) == 0) {
            throw new AssertionError(new String(process.getInputStream().readAllBytes()));
        }
        int port = Integer.parseInt(Files.readString(portFile));
        McpConnection connection = new McpConnection(
                "connection", "installation", "tenant", "owner",
                "http://127.0.0.1:" + port + "/mcp", "encrypted", McpAuthType.BEARER,
                McpConnectionState.ACTIVE, null, null, 4, NOW, NOW, null);
        McpCapabilitySnapshot snapshot = new McpCapabilitySnapshot(
                "snapshot", connection.id(), connection.revision(), "2025-06-18",
                "fixture", null, "1.0", null, "{}",
                "[{\"name\":\"issue_write\",\"readOnly\":false,\"destructive\":true},"
                        + "{\"name\":\"issue_read\",\"readOnly\":true,\"destructive\":false}]",
                "a".repeat(64), NOW);
        McpMarketplaceRepository marketplace = mock(McpMarketplaceRepository.class);
        when(marketplace.findConnection(connection.id())).thenReturn(Optional.of(connection));
        when(marketplace.findInstallation(connection.installationId())).thenReturn(Optional.of(
                new McpInstallation(
                        "installation", "entry", "version", "1.0", "tenant", "owner",
                        "owner", McpInstallationScope.USER, "Fixture",
                        McpInstallationState.INSTALLED, NOW, NOW)));
        McpConnectionQualificationRepository qualifications =
                mock(McpConnectionQualificationRepository.class);
        when(qualifications.findCurrentSnapshot(connection.id()))
                .thenReturn(Optional.of(snapshot));
        McpConnectionAuthorizationService authorization =
                mock(McpConnectionAuthorizationService.class);
        when(authorization.authorize(connection)).thenReturn(
                new McpConnectionAuthorizationService.AuthorizedConnection(
                        connection, Map.of("access_token", "sdk-secret")));
        ObjectMapper json = new ObjectMapper();
        var verifier = new GithubIssueUpdateMcpEffectVerifier(
                marketplace, qualifications, authorization,
                new OfficialSdkMcpRemoteToolGateway(URI::create, json), json, () -> NOW);
        return new Fixture(
                process, requestLog, connection, snapshot,
                new LocalToolEffectVerifierRegistry(List.of(verifier)));
    }

    private static ToolEffectVerifier.Request request(
            McpConnection connection, McpCapabilitySnapshot snapshot, String title) {
        String input = canonical(title);
        return request(connection, snapshot, input, ToolEffectVerifier.sha256(input));
    }

    private static ToolEffectVerifier.Request request(
            McpConnection connection,
            McpCapabilitySnapshot snapshot,
            String input,
            String inputSha256) {
        return new ToolEffectVerifier.Request(
                "tenant", "owner", "run", "step", "ledger", "call", "mcp_call",
                ToolEffectVerifier.sha256("ledger-input"), 3, input, inputSha256,
                new ToolEffectVerifier.McpScope(
                        connection.id(), connection.revision(), snapshot.id(),
                        "sha256:" + snapshot.snapshotSha256(), "issue_write"), NOW);
    }

    private static String canonical(String title) {
        if (title.startsWith("{")) return title;
        return "{\"connectionId\":\"connection\",\"remoteTool\":\"issue_write\","
                + "\"arguments\":{\"method\":\"update\",\"owner\":\"octocat\","
                + "\"repo\":\"demo\",\"issue_number\":42,\"title\":\"" + title
                + "\",\"body\":\"Verified body\",\"state\":\"open\"}}";
    }

    private record Fixture(
            Process process,
            Path requestLog,
            McpConnection connection,
            McpCapabilitySnapshot snapshot,
            LocalToolEffectVerifierRegistry registry) implements AutoCloseable {
        @Override public void close() throws Exception {
            process.destroyForcibly();
            process.waitFor();
        }
    }
}
