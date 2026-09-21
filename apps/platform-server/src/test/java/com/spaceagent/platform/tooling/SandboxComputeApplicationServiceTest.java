package com.spaceagent.platform.tooling;

import com.spaceagent.platform.tooling.api.SandboxComputeApplicationApi;
import com.spaceagent.platform.tooling.application.SandboxComputeApplicationService;
import com.spaceagent.platform.tooling.domain.SandboxExecutionGateway;
import com.spaceagent.platform.tooling.domain.SandboxExecutionMetadata;
import com.spaceagent.platform.tooling.domain.SandboxExecutionRequest;
import com.spaceagent.platform.tooling.domain.SandboxExecutionResponse;
import com.spaceagent.platform.tooling.domain.SandboxExecutionStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SandboxComputeApplicationServiceTest {

    @Test
    void invokesComputeWithoutOpeningASecondLedgerAndEnforcesPolicy() {
        AtomicReference<SandboxExecutionRequest> captured = new AtomicReference<>();
        SandboxExecutionGateway gateway = request -> {
            captured.set(request);
            return response(request, SandboxExecutionStatus.SUCCEEDED, 0, "ok\n", null);
        };
        var service = new SandboxComputeApplicationService(gateway, () -> "exec-1");

        var result = service.execute(command());

        assertThat(result.status()).isEqualTo("SUCCEEDED");
        assertThat(result.stdout()).isEqualTo("ok\n");
        assertThat(captured.get().executionId()).isEqualTo("exec-1");
        assertThat(captured.get().workspaceRef()).isEqualTo(workspace());
        assertThat(captured.get().resourcePolicy().allowNetwork()).isFalse();
        assertThat(captured.get().resourcePolicy().allowedPaths()).containsExactly(".");
        assertThat(captured.get().resourcePolicy().maxMemoryBytes())
                .isEqualTo(512L * 1024 * 1024);
        assertThat(captured.get().resourcePolicy().maxOutputBytes()).isEqualTo(200_000);
        assertThat(captured.get().resourcePolicy().readOnlyWorkspace()).isFalse();

        service.execute(new SandboxComputeApplicationApi.ComputeCommand(
                "run", "recovery", workspace(), "task", "project-context-snapshot",
                "git", List.of("status", "--porcelain=v1"), 30));
        assertThat(captured.get().resourcePolicy().maxOutputBytes()).isEqualTo(1_000_000);
        assertThat(captured.get().resourcePolicy().readOnlyWorkspace()).isTrue();

        service.execute(new SandboxComputeApplicationApi.ComputeCommand(
                "run", "intake", workspace(), "intake-job", "project-intake-inspection",
                "git", List.of("ls-files", "-z"), 30));
        assertThat(captured.get().resourcePolicy().readOnlyWorkspace()).isTrue();

        service.execute(new SandboxComputeApplicationApi.ComputeCommand(
                "run", "read", workspace(), "task", "workspace-read-file",
                "spaceagent-workspace-tool", List.of("file-read", "README.md", "1000"),
                30, "Zml4dHVyZQ=="));
        assertThat(captured.get().resourcePolicy().readOnlyWorkspace()).isTrue();
        assertThat(captured.get().inputBase64()).isEqualTo("Zml4dHVyZQ==");
    }

    @Test
    void rejectsInvalidCommandsAndMismatchedWorkerResults() {
        var rejecting = new SandboxComputeApplicationService(
                request -> response(
                        request, SandboxExecutionStatus.REJECTED, -1, "", "REJECTED"),
                () -> "exec-1");
        assertThatThrownBy(() -> rejecting.execute(new SandboxComputeApplicationApi.ComputeCommand(
                "run", "call", workspace(), "task", "coding", "bash",
                List.of("-c", "id"), 30)))
                .isInstanceOf(IllegalArgumentException.class);

        var mismatched = new SandboxComputeApplicationService(request ->
                new SandboxExecutionResponse(
                        request.executionId(), "foreign-run", request.toolCallId(), 0,
                        SandboxExecutionStatus.SUCCEEDED, "", "", List.of(),
                        new SandboxExecutionMetadata(1, 2, 1, false, 0), null),
                () -> "exec-2");
        assertThatThrownBy(() -> mismatched.execute(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("correlation");
    }

    private static SandboxComputeApplicationApi.ComputeCommand command() {
        return new SandboxComputeApplicationApi.ComputeCommand(
                "run", "call", workspace(), "task", "coding-run-command",
                "mvn", List.of("test"), 30);
    }

    private static String workspace() {
        return "workspaces/00000000-0000-4000-8000-000000000003";
    }

    private static SandboxExecutionResponse response(
            SandboxExecutionRequest request,
            SandboxExecutionStatus status,
            int exit,
            String stdout,
            String error) {
        return new SandboxExecutionResponse(
                request.executionId(), request.agentRunId(), request.toolCallId(), exit,
                status, stdout, "", List.of(),
                new SandboxExecutionMetadata(1, 2, 1, false, stdout.length()), error);
    }
}
