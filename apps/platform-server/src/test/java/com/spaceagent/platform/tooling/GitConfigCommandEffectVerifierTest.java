package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.application.GitConfigCommandEffectVerifier;
import com.spaceagent.platform.tooling.application.SandboxComputeApplicationService;
import com.spaceagent.platform.tooling.domain.LocalToolEffectVerifierRegistry;
import com.spaceagent.platform.tooling.domain.SandboxExecutionGateway;
import com.spaceagent.platform.tooling.domain.SandboxExecutionMetadata;
import com.spaceagent.platform.tooling.domain.SandboxExecutionRequest;
import com.spaceagent.platform.tooling.domain.SandboxExecutionResponse;
import com.spaceagent.platform.tooling.domain.SandboxExecutionStatus;
import com.spaceagent.platform.tooling.domain.ToolEffectVerifier;
import com.spaceagent.shared.exception.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitConfigCommandEffectVerifierTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private static final String WORKSPACE = "00000000-0000-4000-8000-000000000003";

    @Test
    void provesGitConfigThroughDifferentReadOnlySandboxCommand() {
        AtomicReference<SandboxExecutionRequest> captured = new AtomicReference<>();
        SandboxExecutionGateway gateway = request -> {
            captured.set(request);
            return response(request, 0, "Space Agent\n", "", false);
        };
        LocalToolEffectVerifierRegistry registry = registry(gateway);

        ToolEffectVerifier.Evidence evidence = registry.verify(request(
                List.of("config", "user.name", "Space Agent"), null));

        assertThat(evidence.verdict())
                .isEqualTo(ToolEffectVerifier.Verdict.PROVEN_APPLIED);
        assertThat(captured.get().command()).isEqualTo("git");
        assertThat(captured.get().arguments())
                .containsExactly("config", "--get", "user.name")
                .doesNotContain("Space Agent");
        assertThat(captured.get().resourcePolicy().readOnlyWorkspace()).isTrue();
        assertThat(captured.get().resourcePolicy().allowNetwork()).isFalse();
    }

    @Test
    void mismatchMissingAmbiguityAndUnsupportedCommandsNeverRedispatchOriginal() {
        AtomicReference<SandboxExecutionRequest> captured = new AtomicReference<>();
        LocalToolEffectVerifierRegistry mismatch = registry(request -> {
            captured.set(request);
            return response(request, 0, "Other\n", "", false);
        });
        assertThat(mismatch.verify(request(
                List.of("config", "user.email", "owner@example.com"), null)).verdict())
                .isEqualTo(ToolEffectVerifier.Verdict.PROVEN_NOT_APPLIED);
        assertThat(captured.get().arguments()).containsExactly(
                "config", "--get", "user.email");

        LocalToolEffectVerifierRegistry unavailable = registry(request -> {
            throw new IllegalStateException("worker unavailable");
        });
        assertThat(unavailable.verify(request(
                List.of("config", "user.name", "Space Agent"), null)).verdict())
                .isEqualTo(ToolEffectVerifier.Verdict.INCONCLUSIVE);

        assertThatThrownBy(() -> mismatch.verify(request(
                List.of("push", "origin", "main"), null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("TOOL_EFFECT_VERIFIER_UNSUPPORTED"));
        assertThatThrownBy(() -> mismatch.verify(request(
                List.of("config", "user.name", "Space Agent"),
                ToolEffectVerifier.sha256("stale"))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("TOOL_EFFECT_VERIFIER_SCOPE_MISMATCH"));
    }

    private static LocalToolEffectVerifierRegistry registry(SandboxExecutionGateway gateway) {
        var sandbox = new SandboxComputeApplicationService(gateway, () -> "verify-execution");
        return new LocalToolEffectVerifierRegistry(List.of(
                new GitConfigCommandEffectVerifier(
                        sandbox, new ObjectMapper(), () -> NOW)));
    }

    private static ToolEffectVerifier.Request request(
            List<String> arguments, String digestOverride) {
        String input = "{\"type\":\"RUN_COMMAND\",\"path\":\"\",\"content\":\"\","
                + "\"executable\":\"git\",\"arguments\":[\""
                + String.join("\",\"", arguments) + "\"]}";
        String digest = digestOverride == null
                ? GitConfigCommandEffectVerifier.commandDigest("git", arguments)
                : digestOverride;
        return new ToolEffectVerifier.Request(
                "tenant", "owner", "run", "step", "ledger", "call",
                "workspace-run_command", ToolEffectVerifier.sha256("ledger-input"), 3,
                input, ToolEffectVerifier.sha256(input),
                new ToolEffectVerifier.SandboxCommandScope(
                        WORKSPACE, "workspaces/" + WORKSPACE, "task", digest, "git"), NOW);
    }

    private static SandboxExecutionResponse response(
            SandboxExecutionRequest request,
            int exit,
            String stdout,
            String stderr,
            boolean timedOut) {
        return new SandboxExecutionResponse(
                request.executionId(), request.agentRunId(), request.toolCallId(), exit,
                SandboxExecutionStatus.SUCCEEDED, stdout, stderr, List.of(),
                new SandboxExecutionMetadata(1, 2, 1, timedOut,
                        stdout.length() + stderr.length()), null);
    }
}
