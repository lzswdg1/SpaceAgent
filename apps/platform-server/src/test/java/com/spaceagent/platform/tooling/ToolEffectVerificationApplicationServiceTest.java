package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.api.ClaimToolExecutionCommand;
import com.spaceagent.platform.tooling.api.MarkToolExecutionUnknownCommand;
import com.spaceagent.platform.tooling.api.ToolEffectVerificationApplicationApi;
import com.spaceagent.platform.tooling.application.ToolEffectVerificationApplicationService;
import com.spaceagent.platform.tooling.application.ToolExecutionLedgerService;
import com.spaceagent.platform.tooling.domain.LocalToolEffectVerifierRegistry;
import com.spaceagent.platform.tooling.domain.ToolEffectVerifier;
import com.spaceagent.platform.tooling.domain.ToolExecutionStatus;
import com.spaceagent.platform.tooling.infrastructure.memory.InMemoryToolExecutionLedgerRepository;
import com.spaceagent.shared.exception.BusinessException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolEffectVerificationApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    void appliedProofUsesUnknownRevisionCasAndReplaysWithoutVerifierRerun() {
        Fixture fixture = fixture(ToolEffectVerifier.Verdict.PROVEN_APPLIED);

        var resolved = fixture.service().verify(command(fixture.revision(), fixture.scope()));
        var replay = fixture.service().verify(command(fixture.revision(), fixture.scope()));

        assertThat(resolved.transition()).isEqualTo("APPLIED");
        assertThat(resolved.ledgerStatus()).isEqualTo("SUCCEEDED");
        assertThat(replay.transition()).isEqualTo("CURRENT_TERMINAL");
        assertThat(fixture.verifications()).hasValue(1);
        var stored = fixture.repository().findByRunId("run").getFirst();
        assertThat(stored.reconciliationEvidence().details())
                .containsEntry("verdict", "PROVEN_APPLIED")
                .containsKeys("subjectSha256", "observationSha256")
                .doesNotContainValue("raw observation");
    }

    @Test
    void notAppliedBecomesFailedButStaleAndInconclusiveRemainUnknown() {
        Fixture notApplied = fixture(ToolEffectVerifier.Verdict.PROVEN_NOT_APPLIED);
        assertThat(notApplied.service().verify(command(
                notApplied.revision(), notApplied.scope())).ledgerStatus()).isEqualTo("FAILED");

        Fixture stale = fixture(ToolEffectVerifier.Verdict.PROVEN_APPLIED);
        assertThatThrownBy(() -> stale.service().verify(command(
                stale.revision() + 1, stale.scope())))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("TOOL_EFFECT_VERIFIER_REVISION_CONFLICT"));
        assertThat(stale.repository().findByRunId("run").getFirst().status())
                .isEqualTo(ToolExecutionStatus.UNKNOWN);
        assertThat(stale.verifications()).hasValue(0);

        Fixture inconclusive = fixture(ToolEffectVerifier.Verdict.INCONCLUSIVE);
        assertThatThrownBy(() -> inconclusive.service().verify(command(
                inconclusive.revision(), inconclusive.scope())))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("TOOL_EFFECT_VERIFIER_INCONCLUSIVE"));
        assertThat(inconclusive.repository().findByRunId("run").getFirst().status())
                .isEqualTo(ToolExecutionStatus.UNKNOWN);
    }

    @Test
    void unsupportedRegistryBindingReturnsSafeCodeAndLeavesUnknown() {
        Fixture fixture = fixture(ToolEffectVerifier.Verdict.PROVEN_APPLIED);
        var service = new ToolEffectVerificationApplicationService(
                new ToolExecutionLedgerService(
                        fixture.repository(), () -> "unused", () -> NOW),
                new LocalToolEffectVerifierRegistry(List.of()),
                new ObjectMapper(), () -> NOW);

        assertThatThrownBy(() -> service.verify(command(
                fixture.revision(), fixture.scope())))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("TOOL_EFFECT_VERIFIER_UNSUPPORTED"));
        assertThat(fixture.repository().findByRunId("run").getFirst().status())
                .isEqualTo(ToolExecutionStatus.UNKNOWN);
        assertThat(fixture.verifications()).hasValue(0);
    }

    private static Fixture fixture(ToolEffectVerifier.Verdict verdict) {
        var repository = new InMemoryToolExecutionLedgerRepository();
        var ledger = new ToolExecutionLedgerService(repository, () -> "ledger", () -> NOW);
        String arguments = "{}";
        var claim = ledger.claim(new ClaimToolExecutionCommand(
                "run", "step", "mcp_call", "call", "idem", arguments,
                ToolEffectVerifier.sha256(arguments), 60));
        var unknown = ledger.markUnknown(new MarkToolExecutionUnknownCommand(
                "run", "call", claim.claimToken(), claim.revision(), "ambiguous")).ledger();
        AtomicInteger calls = new AtomicInteger();
        ToolEffectVerifier verifier = new ToolEffectVerifier() {
            public Definition definition() {
                return new Definition(
                        "fixture.verifier", 1, EffectKind.MCP_MUTATION,
                        "mcp_call", "fixture_write", Trust.LOCAL_REVIEWED,
                        "fixture/v1", 1_000, 1_000, 1_000, true);
            }
            public Evidence verify(Request request) {
                calls.incrementAndGet();
                return new Evidence(
                        "fixture.verifier", 1, "fixture/v1", verdict,
                        request.subjectSha256(),
                        verdict == Verdict.INCONCLUSIVE
                                ? null : ToolEffectVerifier.sha256("raw observation"),
                        verdict == Verdict.INCONCLUSIVE ? 0 : 15,
                        verdict == Verdict.INCONCLUSIVE
                                ? "POSTCONDITION_UNKNOWN" : "POSTCONDITION_CHECKED",
                        NOW, NOW.plusMillis(1));
            }
        };
        var scope = new ToolEffectVerifier.McpScope(
                "connection", 1, "snapshot", ToolEffectVerifier.sha256("snapshot"),
                "fixture_write");
        return new Fixture(
                new ToolEffectVerificationApplicationService(
                        ledger, new LocalToolEffectVerifierRegistry(List.of(verifier)),
                        new ObjectMapper(), () -> NOW),
                repository, scope, unknown.revision(), calls);
    }

    private static ToolEffectVerificationApplicationApi.VerifyCommand command(
            long revision, ToolEffectVerifier.Scope scope) {
        return new ToolEffectVerificationApplicationApi.VerifyCommand(
                "tenant", "owner", "run", "call", revision, "proof requested", scope);
    }

    private record Fixture(
            ToolEffectVerificationApplicationService service,
            InMemoryToolExecutionLedgerRepository repository,
            ToolEffectVerifier.McpScope scope,
            long revision,
            AtomicInteger verifications) {
    }
}
