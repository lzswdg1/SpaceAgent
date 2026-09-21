package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.api.ClaimToolExecutionCommand;
import com.spaceagent.platform.tooling.api.CompleteToolExecutionCommand;
import com.spaceagent.platform.tooling.api.MarkToolExecutionUnknownCommand;
import com.spaceagent.platform.tooling.api.ReconcileUnknownToolExecutionCommand;
import com.spaceagent.platform.tooling.api.SandboxToolExecutionCommand;
import com.spaceagent.platform.tooling.api.ToolExecutionClaimView;
import com.spaceagent.platform.tooling.api.ToolEffectVerificationApplicationApi;
import com.spaceagent.platform.tooling.application.SandboxToolExecutionService;
import com.spaceagent.platform.tooling.application.ToolEffectVerificationApplicationService;
import com.spaceagent.platform.tooling.application.ToolExecutionLedgerService;
import com.spaceagent.platform.tooling.domain.SandboxExecutionGateway;
import com.spaceagent.platform.tooling.domain.SandboxExecutionMetadata;
import com.spaceagent.platform.tooling.domain.SandboxExecutionRequest;
import com.spaceagent.platform.tooling.domain.SandboxExecutionResponse;
import com.spaceagent.platform.tooling.domain.SandboxExecutionStatus;
import com.spaceagent.platform.tooling.domain.ToolExecutionClaimDecisionType;
import com.spaceagent.platform.tooling.domain.ToolExecutionReconciliationEvidence;
import com.spaceagent.platform.tooling.domain.ToolExecutionStatus;
import com.spaceagent.platform.tooling.domain.ToolExecutionTransitionType;
import com.spaceagent.platform.tooling.domain.LocalToolEffectVerifierRegistry;
import com.spaceagent.platform.tooling.domain.ToolEffectVerifier;
import com.spaceagent.platform.tooling.infrastructure.persistence.PostgresToolExecutionLedgerRepository;
import com.spaceagent.platform.project.api.WorkspaceToolApplicationApi;
import com.spaceagent.platform.runtime.api.AgentRunView;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeToolReconciliationApplicationApi;
import com.spaceagent.platform.runtime.application.RuntimeToolReconciliationApplicationService;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.orchestration.ExecutionCursor;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PostgreSQL 17 proof for M10-PR1 atomic claims, fencing, UNKNOWN and reconciliation.
 */
@Testcontainers(disabledWithoutDocker = true)
@Execution(ExecutionMode.SAME_THREAD)
class PlatformToolExecutionAtomicClaimPostgresTest {

    private static final Instant NOW = Instant.parse("2026-08-22T02:00:00Z");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("spaceagent_tool_claim")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    @BeforeEach
    void migrateLatest() {
        cleanAndMigrate(null);
    }

    @Test
    void twoRepositoryInstancesAtomicallyProduceOneClaimAndOneBusyDecision() throws Exception {
        createRunAndStep("run-atomic", "step-atomic");
        ToolExecutionLedgerService first = service("jvm-a");
        ToolExecutionLedgerService second = service("jvm-b");
        ClaimToolExecutionCommand command = claimCommand(
                "run-atomic", "step-atomic", "call-atomic", "sha256:atomic", 60);
        CyclicBarrier barrier = new CyclicBarrier(3);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ToolExecutionClaimView> left = executor.submit(() -> {
                barrier.await();
                return first.claim(command);
            });
            Future<ToolExecutionClaimView> right = executor.submit(() -> {
                barrier.await();
                return second.claim(command);
            });
            barrier.await();
            List<ToolExecutionClaimView> decisions = List.of(left.get(), right.get());

            assertEquals(1, decisions.stream()
                    .filter(value -> value.type() == ToolExecutionClaimDecisionType.CLAIMED)
                    .count());
            assertEquals(1, decisions.stream()
                    .filter(value -> value.type() == ToolExecutionClaimDecisionType.BUSY)
                    .count());
            ToolExecutionClaimView claimed = decisions.stream()
                    .filter(value -> value.type() == ToolExecutionClaimDecisionType.CLAIMED)
                    .findFirst().orElseThrow();
            assertNotNull(claimed.claimToken());
            assertEquals(1L, claimed.revision());
            assertTrue(claimed.leaseUntil().isAfter(claimed.ledger().claimedAt()));
            assertEquals("sha256:atomic", claimed.ledger().inputHash());
            assertEquals(1L, countLogical("run-atomic", "call-atomic"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void twoSandboxServicesSharingPostgresInvokeGatewayOnlyOnce() throws Exception {
        createRunAndStep("run-double", "step-double");
        BlockingGateway gateway = new BlockingGateway();
        SandboxToolExecutionService first = sandboxService("jvm-a", gateway);
        SandboxToolExecutionService second = sandboxService("jvm-b", gateway);
        SandboxToolExecutionCommand command = sandboxCommand("run-double", "step-double", "call-double");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> firstResult = executor.submit(() -> first.execute(command));
            assertTrue(gateway.entered.await(10, TimeUnit.SECONDS));

            BusinessException busy = assertThrows(BusinessException.class, () -> second.execute(command));
            assertEquals("TOOL_EXECUTION_IN_PROGRESS", busy.getCode());
            gateway.release.countDown();
            firstResult.get();

            assertEquals(1, gateway.calls.get());
            assertEquals(ToolExecutionStatus.SUCCEEDED, ledger("run-double", "call-double").status());
        } finally {
            gateway.release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void conflictingInputHashNeverOverwritesOriginalClaim() {
        createRunAndStep("run-conflict", "step-conflict");
        ToolExecutionLedgerService service = service("jvm-a");
        ToolExecutionClaimView original = service.claim(claimCommand(
                "run-conflict", "step-conflict", "call-conflict", "sha256:a", 60));

        ToolExecutionClaimView conflict = service.claim(claimCommand(
                "run-conflict", "step-conflict", "call-conflict", "sha256:b", 60));

        assertEquals(ToolExecutionClaimDecisionType.CONFLICT, conflict.type());
        assertEquals("sha256:a", conflict.expectedInputHash());
        assertEquals("sha256:b", conflict.actualInputHash());
        var stored = ledger("run-conflict", "call-conflict");
        assertEquals("sha256:a", stored.inputHash());
        assertEquals(ToolExecutionStatus.RUNNING, stored.status());
        assertEquals(original.claimToken(), stored.claimToken());
        assertEquals(original.revision(), stored.revision());
    }

    @Test
    void everyTerminalStateReplaysWithoutCallingGateway() throws Exception {
        createRunAndStep("run-replay", "step-replay");
        ToolExecutionLedgerService ledgerService = service("jvm-a");
        CountingGateway gateway = new CountingGateway();
        SandboxToolExecutionService sandbox = sandboxService("jvm-b", gateway);
        List<ToolExecutionStatus> terminalStates = List.of(
                ToolExecutionStatus.SUCCEEDED,
                ToolExecutionStatus.FAILED,
                ToolExecutionStatus.TIMED_OUT,
                ToolExecutionStatus.CANCELLED);

        for (int index = 0; index < terminalStates.size(); index++) {
            ToolExecutionStatus status = terminalStates.get(index);
            String callId = "call-replay-" + index;
            SandboxToolExecutionCommand sandboxCommand = sandboxCommand("run-replay", "step-replay", callId);
            String arguments = serializedArguments(sandboxCommand);
            ToolExecutionClaimView claim = ledgerService.claim(new ClaimToolExecutionCommand(
                    "run-replay", "step-replay", "sandbox-echo", callId,
                    "idem-" + callId, arguments, inputHash(arguments), 60));
            var completion = ledgerService.complete(new CompleteToolExecutionCommand(
                    "run-replay", callId, claim.claimToken(), claim.revision(), status,
                    status == ToolExecutionStatus.SUCCEEDED ? "persisted-result" : null,
                    "artifact://" + callId,
                    status == ToolExecutionStatus.SUCCEEDED ? null : "persisted-error"));
            assertEquals(ToolExecutionTransitionType.APPLIED, completion.type());

            var replay = sandbox.execute(sandboxCommand);
            assertEquals(status.name(), replay.status());
            assertEquals(completion.ledger().result(), replay.result());
            assertEquals(completion.ledger().error(), replay.error());
        }
        assertEquals(0, gateway.calls.get());
    }

    @Test
    void validRunningClaimIsBusyAndPreservesFencingMetadata() {
        createRunAndStep("run-busy", "step-busy");
        ToolExecutionLedgerService service = service("jvm-a");
        ClaimToolExecutionCommand command = claimCommand(
                "run-busy", "step-busy", "call-busy", "sha256:busy", 60);
        ToolExecutionClaimView first = service.claim(command);

        ToolExecutionClaimView busy = service("jvm-b").claim(command);

        assertEquals(ToolExecutionClaimDecisionType.BUSY, busy.type());
        var stored = ledger("run-busy", "call-busy");
        assertEquals(first.claimToken(), stored.claimToken());
        assertEquals(first.revision(), stored.revision());
        assertEquals(first.leaseUntil(), stored.leaseUntil());
    }

    @Test
    void expiredRunningClaimBecomesUnknownWithoutASecondClaim() throws Exception {
        createRunAndStep("run-expired", "step-expired");
        SandboxToolExecutionCommand sandboxCommand =
                sandboxCommand("run-expired", "step-expired", "call-expired");
        String arguments = serializedArguments(sandboxCommand);
        ToolExecutionClaimView first = service("jvm-a").claim(new ClaimToolExecutionCommand(
                "run-expired", "step-expired", "sandbox-echo", "call-expired",
                "idem-call-expired", arguments, inputHash(arguments), 60));
        expireLease("run-expired", "call-expired");
        CountingGateway gateway = new CountingGateway();

        BusinessException unknown = assertThrows(
                BusinessException.class,
                () -> sandboxService("jvm-b", gateway).execute(sandboxCommand));

        assertEquals("TOOL_EXECUTION_AMBIGUOUS", unknown.getCode());
        var stored = ledger("run-expired", "call-expired");
        assertEquals(ToolExecutionStatus.UNKNOWN, stored.status());
        assertEquals(first.revision() + 1, stored.revision());
        assertNull(stored.claimToken());
        assertEquals(0, gateway.calls.get());
    }

    @Test
    void wrongClaimTokenCannotComplete() {
        createRunAndStep("run-token", "step-token");
        ToolExecutionLedgerService service = service("jvm-a");
        ToolExecutionClaimView claim = service.claim(claimCommand(
                "run-token", "step-token", "call-token", "sha256:token", 60));

        var transition = service.complete(new CompleteToolExecutionCommand(
                "run-token", "call-token", UUID.randomUUID().toString(), claim.revision(),
                ToolExecutionStatus.SUCCEEDED, "wrong", null, null));

        assertEquals(ToolExecutionTransitionType.CLAIM_LOST, transition.type());
        var stored = ledger("run-token", "call-token");
        assertEquals(ToolExecutionStatus.RUNNING, stored.status());
        assertEquals(claim.revision(), stored.revision());
    }

    @Test
    void staleRevisionCannotComplete() {
        createRunAndStep("run-revision", "step-revision");
        ToolExecutionLedgerService service = service("jvm-a");
        ToolExecutionClaimView claim = service.claim(claimCommand(
                "run-revision", "step-revision", "call-revision", "sha256:revision", 60));

        var transition = service.complete(new CompleteToolExecutionCommand(
                "run-revision", "call-revision", claim.claimToken(), claim.revision() + 1,
                ToolExecutionStatus.SUCCEEDED, "stale", null, null));

        assertEquals(ToolExecutionTransitionType.CLAIM_LOST, transition.type());
        assertEquals(claim.revision(), ledger("run-revision", "call-revision").revision());
    }

    @Test
    void expiredClaimCompletionStoresEvidenceAndRemainsUnknown() {
        createRunAndStep("run-late", "step-late");
        ToolExecutionLedgerService service = service("jvm-a");
        ToolExecutionClaimView claim = service.claim(claimCommand(
                "run-late", "step-late", "call-late", "sha256:late", 60));
        expireLease("run-late", "call-late");

        var transition = service.complete(new CompleteToolExecutionCommand(
                "run-late", "call-late", claim.claimToken(), claim.revision(),
                ToolExecutionStatus.SUCCEEDED, "late-result", "external://late", null));

        assertEquals(ToolExecutionTransitionType.CURRENT_UNKNOWN, transition.type());
        var stored = ledger("run-late", "call-late");
        assertEquals(ToolExecutionStatus.UNKNOWN, stored.status());
        assertNotNull(stored.reconciliationEvidence());
        assertEquals("late-result", stored.reconciliationEvidence().details().get("result"));
    }

    @Test
    void crashAfterSideEffectBeforeTerminalWriteNeverExecutesAgain() throws Exception {
        createRunAndStep("run-crash", "step-crash");
        AtomicInteger sideEffects = new AtomicInteger();
        SandboxToolExecutionCommand command = sandboxCommand("run-crash", "step-crash", "call-crash");
        SandboxToolExecutionService crashing = sandboxService("jvm-a", request -> {
            sideEffects.incrementAndGet();
            throw new SimulatedProcessCrash();
        });
        assertThrows(SimulatedProcessCrash.class, () -> crashing.execute(command));
        assertEquals(ToolExecutionStatus.RUNNING, ledger("run-crash", "call-crash").status());
        expireLease("run-crash", "call-crash");

        BusinessException unknown = assertThrows(
                BusinessException.class,
                () -> sandboxService("jvm-b", request -> {
                    sideEffects.incrementAndGet();
                    return succeeded(request);
                }).execute(command));

        assertEquals("TOOL_EXECUTION_AMBIGUOUS", unknown.getCode());
        assertEquals(1, sideEffects.get());
        assertEquals(ToolExecutionStatus.UNKNOWN, ledger("run-crash", "call-crash").status());
    }

    @Test
    void unknownRequiresEvidenceAndCorrectRevisionForReconciliation() {
        createRunAndStep("run-reconcile", "step-reconcile");
        ToolExecutionLedgerService service = service("jvm-a");
        ToolExecutionClaimView claim = service.claim(claimCommand(
                "run-reconcile", "step-reconcile", "call-reconcile", "sha256:reconcile", 60));
        var unknown = service.markUnknown(new MarkToolExecutionUnknownCommand(
                "run-reconcile", "call-reconcile", claim.claimToken(), claim.revision(),
                "external outcome cannot be observed"));
        assertEquals(ToolExecutionTransitionType.APPLIED, unknown.type());

        ToolExecutionReconciliationEvidence evidence = new ToolExecutionReconciliationEvidence(
                "operator verified external execution",
                "external://execution-1",
                Map.of("source", "provider-status-api"));
        var stale = service.reconcileUnknown(new ReconcileUnknownToolExecutionCommand(
                "run-reconcile", "call-reconcile", "sha256:reconcile",
                unknown.ledger().revision() + 1, ToolExecutionStatus.SUCCEEDED,
                "resolved-result", "artifact://resolved", null,
                evidence, "operator-1", "verified external success"));
        assertEquals(ToolExecutionTransitionType.CLAIM_LOST, stale.type());

        var wrongHash = service.reconcileUnknown(new ReconcileUnknownToolExecutionCommand(
                "run-reconcile", "call-reconcile", "sha256:different",
                unknown.ledger().revision(), ToolExecutionStatus.SUCCEEDED,
                "must-not-apply", null, null, evidence,
                "operator-1", "wrong input hash"));
        assertEquals(ToolExecutionTransitionType.CONFLICT, wrongHash.type());

        assertThrows(NullPointerException.class, () -> new ReconcileUnknownToolExecutionCommand(
                "run-reconcile", "call-reconcile", "sha256:reconcile",
                unknown.ledger().revision(), ToolExecutionStatus.SUCCEEDED,
                "resolved-result", null, null, null,
                "operator-1", "missing evidence"));

        var resolved = service.reconcileUnknown(new ReconcileUnknownToolExecutionCommand(
                "run-reconcile", "call-reconcile", "sha256:reconcile",
                unknown.ledger().revision(), ToolExecutionStatus.SUCCEEDED,
                "resolved-result", "artifact://resolved", null,
                evidence, "operator-1", "verified external success"));
        assertEquals(ToolExecutionTransitionType.APPLIED, resolved.type());
        assertEquals("operator-1", resolved.ledger().resolvedBy());
        assertNotNull(resolved.ledger().resolvedAt());

        var replay = service.claim(claimCommand(
                "run-reconcile", "step-reconcile", "call-reconcile", "sha256:reconcile", 60));
        assertEquals(ToolExecutionClaimDecisionType.REPLAY, replay.type());
        var secondReconcile = service.reconcileUnknown(new ReconcileUnknownToolExecutionCommand(
                "run-reconcile", "call-reconcile", "sha256:reconcile",
                resolved.ledger().revision(), ToolExecutionStatus.FAILED,
                null, null, "must not overwrite", evidence,
                "operator-2", "second resolution"));
        assertEquals(ToolExecutionTransitionType.CURRENT_TERMINAL, secondReconcile.type());
    }

    @Test
    void workspacePostconditionAdapterPersistsOnlyHashedEvidenceThroughPostgresCas() {
        createRunAndStep("run-specific", "step-specific");
        ToolExecutionLedgerService ledgerService = service("jvm-specific");
        String arguments = "{\"workspaceId\":\"00000000-0000-4000-8000-000000000003\","
                + "\"path\":\"report.md\",\"content\":\"verified document\","
                + "\"format\":\"markdown\"}";
        ToolExecutionClaimView claim = ledgerService.claim(new ClaimToolExecutionCommand(
                "run-specific", "step-specific", "document_write", "call-specific",
                "run-specific:call-specific", arguments, "sha256:specific", 60));
        var unknown = ledgerService.markUnknown(new MarkToolExecutionUnknownCommand(
                "run-specific", "call-specific", claim.claimToken(), claim.revision(),
                "ambiguous fixture")).ledger();

        RuntimeApplicationApi runtime = mock(RuntimeApplicationApi.class);
        Instant now = Instant.now();
        when(runtime.findRun("run-specific")).thenReturn(java.util.Optional.of(
                new AgentRunView(
                        "run-specific", "agent-1",
                        "00000000-0000-4000-8000-000000000001",
                        "tenant-1", "owner-1", "conversation", "project", "directory",
                        "00000000-0000-4000-8000-000000000003", "task", "plan", "step",
                        ExecutionCursor.initial(), 0, AgentRunState.WAITING_FOR_USER,
                        null, now, now, null)));
        WorkspaceToolApplicationApi workspace = mock(WorkspaceToolApplicationApi.class);
        when(workspace.readFile(any())).thenReturn(
                new WorkspaceToolApplicationApi.FileReadView(
                        "report.md", "verified document", 17, false));
        var reconciliation = new RuntimeToolReconciliationApplicationService(
                runtime, ledgerService, workspace, OBJECT_MAPPER);

        var resolved = reconciliation.reconcile(
                new RuntimeToolReconciliationApplicationApi.ReconcileCommand(
                        "tenant-1", "owner-1", "run-specific", "call-specific",
                        unknown.revision(), "verified in Workspace"));

        assertEquals("SUCCEEDED", resolved.status());
        assertEquals("document_utf8_sha256", resolved.verifier());
        String evidence = new JdbcTemplate(newDataSource()).queryForObject("""
                SELECT reconciliation_evidence::text
                FROM platform_tool_execution_ledger
                WHERE agent_run_id='run-specific' AND tool_call_id='call-specific'
                """, String.class);
        assertNotNull(evidence);
        assertTrue(evidence.contains("expectedSha256"));
        assertTrue(evidence.contains("actualSha256"));
        assertTrue(!evidence.contains("verified document"));
    }

    @Test
    void migrationPreservesTerminalRowsAndConservativelyMarksLegacyNonTerminalRowsUnknown() {
        cleanAndMigrate(MigrationVersion.fromVersion("1000"));
        createRunAndStep("run-migration", "step-migration");
        insertLegacyLedger("ledger-success", "run-migration", "step-migration", "call-success",
                "sha256:success", "SUCCEEDED");
        insertLegacyLedger("ledger-failed", "run-migration", "step-migration", "call-failed",
                "sha256:failed", "FAILED");
        insertLegacyLedger("ledger-timeout", "run-migration", "step-migration", "call-timeout",
                "sha256:timeout", "TIMED_OUT");
        insertLegacyLedger("ledger-cancelled", "run-migration", "step-migration", "call-cancelled",
                "sha256:cancelled", "CANCELLED");
        insertLegacyLedger("ledger-pending", "run-migration", "step-migration", "call-pending",
                "sha256:pending", "PENDING");
        insertLegacyLedger("ledger-running", "run-migration", "step-migration", "call-running",
                "sha256:running", "RUNNING");

        flyway(null).migrate();

        Map<String, ToolExecutionStatus> terminalStatuses = Map.of(
                "success", ToolExecutionStatus.SUCCEEDED,
                "failed", ToolExecutionStatus.FAILED,
                "timeout", ToolExecutionStatus.TIMED_OUT,
                "cancelled", ToolExecutionStatus.CANCELLED);
        for (Map.Entry<String, ToolExecutionStatus> terminal : terminalStatuses.entrySet()) {
            var stored = ledger("run-migration", "call-" + terminal.getKey());
            assertEquals("sha256:" + terminal.getKey(), stored.inputHash());
            assertEquals(terminal.getValue(), stored.status());
            assertEquals(1L, stored.revision());
        }
        for (String nonTerminal : List.of("pending", "running")) {
            var stored = ledger("run-migration", "call-" + nonTerminal);
            assertEquals("sha256:" + nonTerminal, stored.inputHash());
            assertEquals(ToolExecutionStatus.UNKNOWN, stored.status());
            assertNull(stored.claimToken());
            assertNotNull(stored.reconciliationEvidence());
            assertEquals(2L, stored.revision());
        }
        Map<String, String> columnTypes = new JdbcTemplate(newDataSource()).query(
                """
                SELECT column_name, data_type
                  FROM information_schema.columns
                 WHERE table_name = 'platform_tool_execution_ledger'
                   AND column_name IN ('claim_token', 'lease_until', 'updated_at')
                """,
                rs -> {
                    Map<String, String> values = new LinkedHashMap<>();
                    while (rs.next()) {
                        values.put(rs.getString(1), rs.getString(2));
                    }
                    return values;
                });
        assertEquals("uuid", columnTypes.get("claim_token"));
        assertEquals("timestamp with time zone", columnTypes.get("lease_until"));
        assertEquals("timestamp with time zone", columnTypes.get("updated_at"));
    }

    @Test
    void capabilityProofUsesPostgresUnknownRevisionCas() throws Exception {
        createRunAndStep("run-proof", "step-proof");
        ToolExecutionLedgerService ledgerService = service("proof-jvm");
        String arguments = "{}";
        var claim = ledgerService.claim(new ClaimToolExecutionCommand(
                "run-proof", "step-proof", "mcp_call", "call-proof", "idem-proof",
                arguments, inputHash(arguments), 60));
        var unknown = ledgerService.markUnknown(new MarkToolExecutionUnknownCommand(
                "run-proof", "call-proof", claim.claimToken(), claim.revision(),
                "ambiguous")).ledger();
        AtomicInteger verifierCalls = new AtomicInteger();
        ToolEffectVerifier verifier = new ToolEffectVerifier() {
            public Definition definition() {
                return new Definition(
                        "postgres.fixture.verifier", 1, EffectKind.MCP_MUTATION,
                        "mcp_call", "fixture_write", Trust.LOCAL_REVIEWED,
                        "postgres/v1", 1_000, 1_000, 1_000, true);
            }
            public Evidence verify(Request request) {
                verifierCalls.incrementAndGet();
                return new Evidence(
                        "postgres.fixture.verifier", 1, "postgres/v1",
                        Verdict.PROVEN_APPLIED, request.subjectSha256(),
                        ToolEffectVerifier.sha256("postgres observation"), 20,
                        "POSTCONDITION_MATCHED", NOW, NOW.plusMillis(1));
            }
        };
        var application = new ToolEffectVerificationApplicationService(
                ledgerService, new LocalToolEffectVerifierRegistry(List.of(verifier)),
                OBJECT_MAPPER, () -> NOW);
        var scope = new ToolEffectVerifier.McpScope(
                "connection", 1, "snapshot", ToolEffectVerifier.sha256("snapshot"),
                "fixture_write");

        BusinessException stale = assertThrows(BusinessException.class, () -> application.verify(
                new ToolEffectVerificationApplicationApi.VerifyCommand(
                        "tenant", "owner", "run-proof", "call-proof",
                        unknown.revision() + 1, "stale", scope)));
        assertEquals("TOOL_EFFECT_VERIFIER_REVISION_CONFLICT", stale.getCode());
        assertEquals(0, verifierCalls.get());
        assertEquals(ToolExecutionStatus.UNKNOWN,
                ledger("run-proof", "call-proof").status());

        var resolved = application.verify(new ToolEffectVerificationApplicationApi.VerifyCommand(
                "tenant", "owner", "run-proof", "call-proof",
                unknown.revision(), "verified", scope));
        assertEquals("SUCCEEDED", resolved.ledgerStatus());
        assertEquals(ToolExecutionTransitionType.APPLIED,
                ToolExecutionTransitionType.valueOf(resolved.transition()));
        assertEquals(1, verifierCalls.get());
        assertEquals("PROVEN_APPLIED", ledger("run-proof", "call-proof")
                .reconciliationEvidence().details().get("verdict"));
    }

    private ToolExecutionLedgerService service(String owner) {
        return new ToolExecutionLedgerService(
                newRepository(),
                new UuidGenerator(),
                () -> owner);
    }

    private SandboxToolExecutionService sandboxService(String owner, SandboxExecutionGateway gateway) {
        return new SandboxToolExecutionService(
                service(owner),
                gateway,
                new UuidGenerator(),
                OBJECT_MAPPER);
    }

    private PostgresToolExecutionLedgerRepository newRepository() {
        DataSource dataSource = newDataSource();
        return new PostgresToolExecutionLedgerRepository(
                new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource),
                OBJECT_MAPPER);
    }

    private ClaimToolExecutionCommand claimCommand(
            String runId,
            String stepId,
            String callId,
            String inputHash,
            long leaseSeconds) {
        return new ClaimToolExecutionCommand(
                runId, stepId, "write-file", callId, "idem-" + callId,
                "{\"path\":\"README.md\"}", inputHash, leaseSeconds);
    }

    private SandboxToolExecutionCommand sandboxCommand(String runId, String stepId, String callId) {
        return new SandboxToolExecutionCommand(
                runId, stepId, "sandbox-echo", callId, "idem-" + callId,
                "echo", List.of("hello"), null, null, 5);
    }

    private static String serializedArguments(SandboxToolExecutionCommand command) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("command", command.command());
        payload.put("args", command.arguments());
        return OBJECT_MAPPER.writeValueAsString(payload);
    }

    private static String inputHash(String arguments) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(arguments.getBytes(StandardCharsets.UTF_8)));
    }

    private void createRunAndStep(String runId, String stepId) {
        JdbcTemplate jdbc = new JdbcTemplate(newDataSource());
        Timestamp now = Timestamp.from(NOW);
        Boolean canonicalAgentReference = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_name = 'platform_agent_runs' AND column_name = 'agent_id'
                )
                """, Boolean.class);
        if (Boolean.TRUE.equals(canonicalAgentReference)) {
            jdbc.update("""
                    INSERT INTO platform_agent_definitions (
                        id, owner_id, tenant_id, name, description, status,
                        revision, created_at, updated_at, archived_at
                    ) VALUES ('agent-1', 'owner-1', 'tenant-1', 'tool-ledger-agent', NULL,
                              'ACTIVE', 1, ?, ?, NULL)
                    ON CONFLICT (id) DO NOTHING
                    """, now, now);
            jdbc.update("""
                    INSERT INTO platform_agent_runs (
                        id, agent_id, owner_id, conversation_id, project_id, task_id,
                        state, failure_reason, created_at, updated_at, completed_at
                    ) VALUES (?, ?, ?, ?, NULL, NULL, 'IN_PROGRESS', NULL, ?, ?, NULL)
                    """, runId, "agent-1", "owner-1", "conversation-1", now, now);
        } else {
            jdbc.update("""
                    INSERT INTO platform_agent_runs (
                        id, agent_definition_id, owner_id, conversation_id, project_id, task_id,
                        state, failure_reason, created_at, updated_at, completed_at
                    ) VALUES (?, ?, ?, ?, NULL, NULL, 'IN_PROGRESS', NULL, ?, ?, NULL)
                    """, runId, "agent-1", "owner-1", "conversation-1", now, now);
        }
        jdbc.update("""
                INSERT INTO platform_run_steps (
                    id, agent_run_id, sequence, type, state, created_at, completed_at
                ) VALUES (?, ?, 0, 'tool-test', 'PENDING', ?, NULL)
                """, stepId, runId, now);
    }

    private void insertLegacyLedger(
            String id,
            String runId,
            String stepId,
            String callId,
            String inputHash,
            String status) {
        new JdbcTemplate(newDataSource()).update("""
                INSERT INTO platform_tool_execution_ledger (
                    id, agent_run_id, run_step_id, tool_name, tool_call_id,
                    idempotency_key, arguments, input_hash, status, result, result_ref, error,
                    started_at, completed_at
                ) VALUES (?, ?, ?, 'legacy-tool', ?, ?, '{}', ?, ?, ?, NULL, NULL, ?, ?)
                """,
                id, runId, stepId, callId, "idem-" + callId, inputHash, status,
                "SUCCEEDED".equals(status) ? "legacy-result" : null,
                Timestamp.from(NOW),
                isTerminal(status) ? Timestamp.from(NOW.plusSeconds(1)) : null);
    }

    private long countLogical(String runId, String callId) {
        Number count = new JdbcTemplate(newDataSource()).queryForObject(
                "SELECT count(*) FROM platform_tool_execution_ledger WHERE agent_run_id = ? AND tool_call_id = ?",
                Number.class,
                runId,
                callId);
        return count == null ? 0 : count.longValue();
    }

    private com.spaceagent.platform.tooling.domain.ToolExecutionLedger ledger(String runId, String callId) {
        return newRepository().findByRunIdAndToolCallId(runId, callId).orElseThrow();
    }

    private void expireLease(String runId, String callId) {
        new JdbcTemplate(newDataSource()).update("""
                UPDATE platform_tool_execution_ledger
                   SET lease_until = clock_timestamp() - INTERVAL '1 second'
                 WHERE agent_run_id = ? AND tool_call_id = ?
                """, runId, callId);
    }

    private void cleanAndMigrate(MigrationVersion target) {
        Flyway flyway = flyway(target);
        flyway.clean();
        flyway.migrate();
    }

    private Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .cleanDisabled(false)
                .dataSource(newDataSource())
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static boolean isTerminal(String status) {
        return List.of("SUCCEEDED", "FAILED", "TIMED_OUT", "CANCELLED").contains(status);
    }

    private static SandboxExecutionResponse succeeded(SandboxExecutionRequest request) {
        long now = System.currentTimeMillis();
        return new SandboxExecutionResponse(
                request.executionId(), request.agentRunId(), request.toolCallId(), 0,
                SandboxExecutionStatus.SUCCEEDED, "hello\n", "", List.of(),
                new SandboxExecutionMetadata(now, now, 0, false, 6), null);
    }

    private static DriverManagerDataSource newDataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    private static final class CountingGateway implements SandboxExecutionGateway {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public SandboxExecutionResponse execute(SandboxExecutionRequest request) {
            calls.incrementAndGet();
            return succeeded(request);
        }
    }

    private static final class BlockingGateway implements SandboxExecutionGateway {
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public SandboxExecutionResponse execute(SandboxExecutionRequest request) {
            calls.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test gateway release timed out");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test gateway interrupted", error);
            }
            return succeeded(request);
        }
    }

    private static final class SimulatedProcessCrash extends Error {
    }
}
