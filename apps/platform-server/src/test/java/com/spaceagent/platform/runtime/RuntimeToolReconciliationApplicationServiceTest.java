package com.spaceagent.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.project.api.WorkspaceSandboxExecutionException;
import com.spaceagent.platform.project.api.WorkspaceToolApplicationApi;
import com.spaceagent.platform.runtime.api.AgentRunView;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeToolReconciliationApplicationApi;
import com.spaceagent.platform.runtime.application.RuntimeToolReconciliationApplicationService;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.orchestration.ExecutionCursor;
import com.spaceagent.platform.tooling.api.ClaimToolExecutionCommand;
import com.spaceagent.platform.tooling.api.MarkToolExecutionUnknownCommand;
import com.spaceagent.platform.tooling.application.ToolExecutionLedgerService;
import com.spaceagent.platform.tooling.infrastructure.memory.InMemoryToolExecutionLedgerRepository;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeToolReconciliationApplicationServiceTest {

    @Test
    void verifiesDocumentWriteByExactUtf8PostconditionAndResolvesUnknown() {
        Fixture fixture = fixture(
                "document_write",
                "{\"workspaceId\":\"00000000-0000-4000-8000-000000000003\","
                        + "\"path\":\"report.md\",\"content\":\"verified 文档\","
                        + "\"format\":\"markdown\"}");
        when(fixture.workspace().readFile(any())).thenReturn(
                new WorkspaceToolApplicationApi.FileReadView(
                        "report.md", "verified 文档", 15, false));

        var resolved = fixture.service().reconcile(command(fixture.revision(), "document verified"));

        assertThat(resolved.status()).isEqualTo("SUCCEEDED");
        assertThat(resolved.verifier()).isEqualTo("document_utf8_sha256");
        assertThat(resolved.evidence()).containsEntry("postcondition", "matched");
        assertThat(resolved.evidence().get("expectedSha256"))
                .isEqualTo(resolved.evidence().get("actualSha256"));
        assertThat(fixture.ledger().findByRunId("run-1").getFirst().result())
                .doesNotContain("verified 文档");
        var replay = fixture.service().reconcile(command(fixture.revision(), "response lost"));
        assertThat(replay.transition()).isEqualTo("CURRENT_TERMINAL");
        assertThat(replay.verifier()).isEqualTo("existing_reconciliation");
        verify(fixture.workspace(), org.mockito.Mockito.times(1)).readFile(any());
    }

    @Test
    void mismatchAndUnsupportedToolRemainUnknownWithoutLedgerMutation() {
        Fixture mismatch = fixture(
                "workspace-write_file",
                "{\"type\":\"WRITE_FILE\",\"path\":\"a.txt\","
                        + "\"content\":\"expected\",\"executable\":\"\",\"arguments\":[]}");
        when(mismatch.workspace().readFile(any())).thenReturn(
                new WorkspaceToolApplicationApi.FileReadView("a.txt", "other", 5, false));
        assertThatThrownBy(() -> mismatch.service().reconcile(
                command(mismatch.revision(), "check write")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("TOOL_RECONCILIATION_INCONCLUSIVE"));
        assertThat(mismatch.ledger().findByRunId("run-1").getFirst().status().name())
                .isEqualTo("UNKNOWN");

        Fixture unsupported = fixture(
                "mcp_call",
                "{\"connectionId\":\"00000000-0000-4000-8000-000000000004\","
                        + "\"remoteTool\":\"write\",\"arguments\":{}}");
        assertThatThrownBy(() -> unsupported.service().reconcile(
                command(unsupported.revision(), "cannot verify remote mutation")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("TOOL_RECONCILIATION_UNSUPPORTED"));
        verify(unsupported.workspace(), never()).readFile(any());
        assertThat(unsupported.ledger().findByRunId("run-1").getFirst().status().name())
                .isEqualTo("UNKNOWN");

        Fixture wrongWorkspace = fixture(
                "document_write",
                "{\"workspaceId\":\"00000000-0000-4000-8000-000000000099\","
                        + "\"path\":\"report.md\",\"content\":\"expected\","
                        + "\"format\":\"markdown\"}");
        assertThatThrownBy(() -> wrongWorkspace.service().reconcile(
                command(wrongWorkspace.revision(), "wrong workspace")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("TOOL_RECONCILIATION_SCOPE_MISMATCH"));
        verify(wrongWorkspace.workspace(), never()).readFile(any());
    }

    @Test
    void verifiesDeleteOnlyFromTrustworthyFileNotFoundPostcondition() {
        Fixture fixture = fixture(
                "workspace-delete_file",
                "{\"type\":\"DELETE_FILE\",\"path\":\"obsolete.txt\","
                        + "\"content\":\"\",\"executable\":\"\",\"arguments\":[]}");
        when(fixture.workspace().readFile(any())).thenThrow(
                new WorkspaceSandboxExecutionException("WORKSPACE_FILE_NOT_FOUND", false));

        var resolved = fixture.service().reconcile(command(fixture.revision(), "absence verified"));

        assertThat(resolved.status()).isEqualTo("SUCCEEDED");
        assertThat(resolved.verifier()).isEqualTo("workspace_file_absent");
        assertThat(resolved.evidence()).containsEntry("postcondition", "absent");
    }

    private Fixture fixture(String toolName, String arguments) {
        RuntimeApplicationApi runtime = mock(RuntimeApplicationApi.class);
        when(runtime.findRun("run-1")).thenReturn(Optional.of(run()));
        WorkspaceToolApplicationApi workspace = mock(WorkspaceToolApplicationApi.class);
        AtomicInteger ids = new AtomicInteger();
        var ledger = new ToolExecutionLedgerService(
                new InMemoryToolExecutionLedgerRepository(),
                () -> "ledger-" + ids.incrementAndGet(),
                () -> Instant.parse("2026-09-06T00:00:00Z"));
        var claim = ledger.claim(new ClaimToolExecutionCommand(
                "run-1", "step-1", toolName, "call-1", "run-1:call-1",
                arguments, "sha256:input", 60));
        var unknown = ledger.markUnknown(new MarkToolExecutionUnknownCommand(
                "run-1", "call-1", claim.claimToken(), claim.revision(),
                "ambiguous fixture")).ledger();
        return new Fixture(
                new RuntimeToolReconciliationApplicationService(
                        runtime, ledger, workspace, new ObjectMapper()),
                ledger, workspace, unknown.revision());
    }

    private RuntimeToolReconciliationApplicationApi.ReconcileCommand command(
            long revision, String reason) {
        return new RuntimeToolReconciliationApplicationApi.ReconcileCommand(
                "tenant", "user", "run-1", "call-1", revision, reason);
    }

    private AgentRunView run() {
        Instant now = Instant.parse("2026-09-06T00:00:00Z");
        return new AgentRunView(
                "run-1", "agent", "version", "tenant", "user", "conversation",
                "project", "directory", "00000000-0000-4000-8000-000000000003",
                "task", "plan", "step", ExecutionCursor.initial(), 0,
                AgentRunState.WAITING_FOR_USER,
                null, now, now, null);
    }

    private record Fixture(
            RuntimeToolReconciliationApplicationService service,
            ToolExecutionLedgerService ledger,
            WorkspaceToolApplicationApi workspace,
            long revision) {
    }
}
