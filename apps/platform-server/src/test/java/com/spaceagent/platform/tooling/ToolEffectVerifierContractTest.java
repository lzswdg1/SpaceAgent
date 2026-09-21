package com.spaceagent.platform.tooling;

import com.spaceagent.platform.tooling.api.ToolEffectVerificationApplicationApi;
import com.spaceagent.platform.tooling.domain.LocalToolEffectVerifierRegistry;
import com.spaceagent.platform.tooling.domain.ToolEffectVerifier;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolEffectVerifierContractTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    void localReviewedRegistryProducesVersionedHashOnlyMcpEvidence() {
        ToolEffectVerifier.Request request = mcpRequest("{}");
        ToolEffectVerifier verifier = verifier(definition(
                ToolEffectVerifier.Trust.LOCAL_REVIEWED, true));
        LocalToolEffectVerifierRegistry registry =
                new LocalToolEffectVerifierRegistry(List.of(verifier));

        ToolEffectVerifier.Evidence evidence = registry.verify(request);

        assertThat(registry.definitions()).singleElement()
                .extracting(ToolEffectVerifier.Definition::verifierVersion)
                .isEqualTo(1);
        assertThat(evidence.verdict())
                .isEqualTo(ToolEffectVerifier.Verdict.PROVEN_APPLIED);
        assertThat(evidence.subjectSha256()).isEqualTo(request.subjectSha256());
        assertThat(evidence.observationSha256()).startsWith("sha256:");
    }

    @Test
    void registryRejectsRemoteWritableDuplicateAndMismatchedEvidence() {
        assertThatThrownBy(() -> new LocalToolEffectVerifierRegistry(List.of(
                verifier(definition(ToolEffectVerifier.Trust.REMOTE_DECLARED, true)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LocalToolEffectVerifierRegistry(List.of(
                verifier(definition(ToolEffectVerifier.Trust.LOCAL_REVIEWED, false)))))
                .isInstanceOf(IllegalArgumentException.class);
        ToolEffectVerifier first = verifier(definition(
                ToolEffectVerifier.Trust.LOCAL_REVIEWED, true));
        assertThatThrownBy(() -> new LocalToolEffectVerifierRegistry(List.of(first, first)))
                .isInstanceOf(IllegalArgumentException.class);

        ToolEffectVerifier wrongEvidence = new ToolEffectVerifier() {
            @Override public Definition definition() {
                return ToolEffectVerifierContractTest.definition(Trust.LOCAL_REVIEWED, true);
            }
            @Override public Evidence verify(Request request) {
                return evidence(ToolEffectVerifier.sha256("another-subject"));
            }
        };
        assertThatThrownBy(() -> new LocalToolEffectVerifierRegistry(
                List.of(wrongEvidence)).verify(mcpRequest("{}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exactInputScopesAndPublicCommandCannotSupplyVerdictOrEvidence() {
        assertThatThrownBy(() -> mcpRequest("changed", ToolEffectVerifier.sha256("{}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolEffectVerifier.SandboxCommandScope(
                "workspace", "workspaces/workspace", "task",
                ToolEffectVerifier.sha256("command"), "sh/path"))
                .isInstanceOf(IllegalArgumentException.class);

        var command = new ToolEffectVerificationApplicationApi.VerifyCommand(
                "tenant", "owner", "run", "call", 7, "operator requested proof",
                new ToolEffectVerifier.McpScope(
                        "connection", 1, "snapshot", ToolEffectVerifier.sha256("snapshot"),
                        "issue_create"));
        assertThat(command.getClass().getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("tenantId", "ownerUserId", "agentRunId", "toolCallId",
                        "expectedLedgerRevision", "reason", "scope")
                .doesNotContain("verdict", "resolution", "verifierId", "evidence");
    }

    private static ToolEffectVerifier verifier(ToolEffectVerifier.Definition definition) {
        return new ToolEffectVerifier() {
            @Override public Definition definition() {
                return definition;
            }
            @Override public Evidence verify(Request request) {
                return evidence(request.subjectSha256());
            }
        };
    }

    private static ToolEffectVerifier.Definition definition(
            ToolEffectVerifier.Trust trust, boolean readOnly) {
        return new ToolEffectVerifier.Definition(
                "mcp.issue.create.postcondition", 1,
                ToolEffectVerifier.EffectKind.MCP_MUTATION, "mcp_call", "issue_create", trust,
                "mcp-postcondition/v1", 10_000, 2_000, 5_000, readOnly);
    }

    private static ToolEffectVerifier.Request mcpRequest(String canonicalInput) {
        return mcpRequest(canonicalInput, ToolEffectVerifier.sha256(canonicalInput));
    }

    private static ToolEffectVerifier.Request mcpRequest(
            String canonicalInput, String canonicalInputSha256) {
        return new ToolEffectVerifier.Request(
                "tenant", "owner", "run", "step", "ledger", "call", "mcp_call",
                ToolEffectVerifier.sha256("ledger-input"), 3,
                canonicalInput, canonicalInputSha256,
                new ToolEffectVerifier.McpScope(
                        "connection", 4, "snapshot", ToolEffectVerifier.sha256("snapshot"),
                        "issue_create"), NOW);
    }

    private static ToolEffectVerifier.Evidence evidence(String subjectSha256) {
        return new ToolEffectVerifier.Evidence(
                "mcp.issue.create.postcondition", 1, "mcp-postcondition/v1",
                ToolEffectVerifier.Verdict.PROVEN_APPLIED, subjectSha256,
                ToolEffectVerifier.sha256("observed"), 8,
                "POSTCONDITION_MATCHED", NOW, NOW.plusMillis(10));
    }
}
