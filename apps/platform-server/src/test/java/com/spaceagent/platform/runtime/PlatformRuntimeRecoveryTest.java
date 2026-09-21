package com.spaceagent.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.conversation.api.ConversationContextSnapshotApplicationApi;
import com.spaceagent.platform.conversation.api.ConversationContextSnapshotView;
import com.spaceagent.platform.conversation.api.SaveConversationContextSnapshotCommand;
import com.spaceagent.platform.conversation.application.ConversationContextSnapshotService;
import com.spaceagent.platform.conversation.infrastructure.memory.InMemoryConversationContextSnapshotRepository;
import com.spaceagent.platform.runtime.api.AcceptRecoveryCommand;
import com.spaceagent.platform.runtime.api.AgentRunView;
import com.spaceagent.platform.runtime.api.CheckpointView;
import com.spaceagent.platform.runtime.api.CompleteAgentRunCommand;
import com.spaceagent.platform.runtime.api.CompleteHandoffCommand;
import com.spaceagent.platform.runtime.api.CompleteRunStepCommand;
import com.spaceagent.platform.runtime.api.CreateCheckpointCommand;
import com.spaceagent.platform.runtime.api.CreateHandoffCommand;
import com.spaceagent.platform.runtime.api.HandoffView;
import com.spaceagent.platform.runtime.api.RecoveryResumeStateView;
import com.spaceagent.platform.runtime.api.RecoveryView;
import com.spaceagent.platform.runtime.api.RequestRecoveryCommand;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeOwnershipPort;
import com.spaceagent.platform.runtime.api.RunStepView;
import com.spaceagent.platform.runtime.api.StartAgentRunCommand;
import com.spaceagent.platform.runtime.api.StartRunStepCommand;
import com.spaceagent.platform.runtime.api.AcceptHandoffCommand;
import com.spaceagent.platform.runtime.application.RuntimeApplicationService;
import com.spaceagent.platform.runtime.application.RuntimeContinuationWorker;
import com.spaceagent.platform.runtime.application.RuntimeCoordinationApplicationService;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.HandoffSnapshot;
import com.spaceagent.platform.runtime.domain.HandoffState;
import com.spaceagent.platform.runtime.domain.HandoffTestStatus;
import com.spaceagent.platform.runtime.domain.RecoveryState;
import com.spaceagent.platform.runtime.domain.RunStepState;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryRuntimeLedgerRepository;
import com.spaceagent.platform.runtime.infrastructure.RuntimeCoordinationProperties;
import com.spaceagent.platform.tooling.api.ClaimToolExecutionCommand;
import com.spaceagent.platform.tooling.api.CompleteToolExecutionCommand;
import com.spaceagent.platform.tooling.api.MarkToolExecutionUnknownCommand;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerView;
import com.spaceagent.platform.tooling.application.ToolExecutionLedgerService;
import com.spaceagent.platform.tooling.domain.ToolExecutionClaimDecisionType;
import com.spaceagent.platform.tooling.domain.ToolExecutionStatus;
import com.spaceagent.platform.tooling.domain.ToolExecutionTransitionType;
import com.spaceagent.platform.tooling.infrastructure.memory.InMemoryToolExecutionLedgerRepository;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused M5 runtime foundation tests: durable run/step/checkpoint/recovery behavior,
 * explicit cancellation, tool-ledger idempotency and ambiguity, conversation snapshot
 * persistence, and typed handoffs.
 */
class PlatformRuntimeRecoveryTest {

    private static final Instant NOW = Instant.parse("2026-08-18T09:00:00Z");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void runCheckpointAndRecoveryResumeFromLatestCheckpoint() {
        InMemoryRuntimeLedgerRepository repository =
                new InMemoryRuntimeLedgerRepository(fixedTimeProvider());
        RuntimeApplicationApi api = runtimeApi(repository);
        AgentRunView run = api.startRun(new StartAgentRunCommand(
                "tenant-1", "owner-1", "agent-1", "version-1", "conversation-1",
                null, null, null, null));

        api.markRunInProgress(run.id());
        RunStepView step = api.startStep(new StartRunStepCommand(run.id(), "compile"));
        assertEquals(RunStepState.PENDING, step.state());

        CheckpointView checkpoint = api.createCheckpoint(
                new CreateCheckpointCommand(run.id(), "{\"phase\":\"compile-in-progress\"}"));
        assertEquals(0, checkpoint.sequence());
        assertEquals(checkpoint, api.findLatestCheckpoint(run.id()).orElseThrow());

        RecoveryView recovery = api.requestRecovery(
                new RequestRecoveryCommand(run.id(), "simulated JVM restart"));
        assertEquals(RecoveryState.IN_PROGRESS, recovery.state());
        assertEquals(AgentRunState.RECOVERING, api.findRun(run.id()).orElseThrow().state());

        RecoveryResumeStateView resumeState = api.reconstructResumeState(run.id()).orElseThrow();
        assertEquals(run.id(), resumeState.configurationSnapshotId());
        assertEquals("conversation-1", resumeState.conversationId());
        assertEquals("compile-in-progress", resumeState.resumePhase());
        assertEquals(step.id(), resumeState.currentRunStepId());

        RecoveryView resumed = api.acceptRecovery(new AcceptRecoveryCommand(
                run.id(), recovery.id(), resumeState));
        assertEquals(RecoveryState.COMPLETED, resumed.state());
        assertEquals(AgentRunState.RECOVERING, api.findRun(run.id()).orElseThrow().state());
        var coordination = new RuntimeCoordinationApplicationService(
                repository, sequentialIdGenerator("coordination"), fixedTimeProvider(), OBJECT_MAPPER);
        RuntimeCoordinationProperties properties = new RuntimeCoordinationProperties();
        properties.setWorkerId("recovery-worker");
        new RuntimeContinuationWorker(coordination, api, properties).processOne();
        assertEquals(AgentRunState.IN_PROGRESS, api.findRun(run.id()).orElseThrow().state());
        assertTrue(((RuntimeOwnershipPort) api).canObserve(run.id(), "tenant-1", "owner-1"));
        assertFalse(((RuntimeOwnershipPort) api).canObserve(run.id(), "tenant-2", "owner-1"));
        assertFalse(((RuntimeOwnershipPort) api).canObserve(run.id(), "tenant-1", "owner-2"));
    }

    @Test
    void ordinaryChatRunUsesConversationWithoutFabricatedProjectOrTask() {
        RuntimeApplicationApi api = runtimeApi();
        AgentRunView run = api.startRun(new StartAgentRunCommand(
                "owner-1", "agent-1", "version-1", "conversation-1", null, null));

        assertEquals("conversation-1", run.conversationId());
        assertEquals(run.id(), run.configurationSnapshotId());
        assertNull(run.projectId());
        assertNull(run.taskId());
    }

    @Test
    void newRunCommandMayResolveCurrentAgentConfigurationAtAdmission() {
        assertNull(new StartAgentRunCommand(
                "owner-1", "agent-1", null, "conversation-1", null, null).configurationSnapshotId());
    }

    @Test
    void recoveryWithoutCheckpointDeterministicallyFailsRun() {
        RuntimeApplicationApi api = runtimeApi();
        AgentRunView run = api.startRun(new StartAgentRunCommand(
                "owner-1", "agent-1", "version-1", "conversation-1", null, null));
        api.markRunInProgress(run.id());

        RecoveryView recovery = api.requestRecovery(
                new RequestRecoveryCommand(run.id(), "no durable progress"));

        assertEquals(RecoveryState.FAILED, recovery.state());
        assertTrue(recovery.reason().contains("No checkpoint available"));
        AgentRunView failed = api.findRun(run.id()).orElseThrow();
        assertEquals(AgentRunState.FAILED, failed.state());
        assertEquals(recovery.reason(), failed.failureReason());
    }

    @Test
    void cancelWritesExplicitTerminalState() {
        RuntimeApplicationApi api = runtimeApi();
        AgentRunView run = api.startRun(new StartAgentRunCommand(
                "owner-1", "agent-1", "version-1", "conversation-1", null, null));
        api.markRunInProgress(run.id());

        AgentRunView cancelled = api.cancel(
                new com.spaceagent.platform.runtime.api.CancelAgentRunCommand(
                        run.id(), "user cancelled"));

        assertEquals(AgentRunState.CANCELLED, cancelled.state());
        assertEquals("user cancelled", cancelled.failureReason());
        assertEquals(cancelled.completedAt(), api.findRun(run.id()).orElseThrow().completedAt());
    }

    @Test
    void waitingForUserWritesDurableWaitingStateAndCanResume() {
        RuntimeApplicationApi api = runtimeApi();
        AgentRunView run = api.startRun(new StartAgentRunCommand(
                "owner-1", "agent-1", "version-1", "conversation-1", null, null));
        api.markRunInProgress(run.id());

        AgentRunView waiting = api.markRunWaitingForUser(run.id());
        assertEquals(AgentRunState.WAITING_FOR_USER, waiting.state());
        assertEquals(AgentRunState.IN_PROGRESS, api.markRunInProgress(run.id()).state());
    }

    @Test
    void toolLedgerIsIdempotentForTheSameRunAndToolCall() {
        ToolExecutionLedgerApplicationApi api = toolApi();
        ClaimToolExecutionCommand command = new ClaimToolExecutionCommand(
                "run-1", "step-1", "write-file", "call-1", "idem-call-1",
                "{\"path\":\"README.md\"}", "sha256:arguments", 60);

        var first = api.claim(command);
        var duplicate = api.claim(command);
        assertEquals(ToolExecutionClaimDecisionType.CLAIMED, first.type());
        assertEquals(ToolExecutionClaimDecisionType.BUSY, duplicate.type());
        assertEquals(first.ledger().id(), duplicate.ledger().id());

        var completion = api.complete(new CompleteToolExecutionCommand(
                "run-1", "call-1", first.claimToken(), first.revision(),
                ToolExecutionStatus.SUCCEEDED, "{\"ok\":true}",
                "artifact://run-1/call-1", null));
        assertEquals(ToolExecutionTransitionType.APPLIED, completion.type());
        ToolExecutionLedgerView completed = completion.ledger();
        assertEquals(ToolExecutionStatus.SUCCEEDED, completed.status());
        assertEquals(completed.id(), api.findById(completed.id()).orElseThrow().id());

        var replay = api.claim(command);
        assertEquals(ToolExecutionClaimDecisionType.REPLAY, replay.type());
        assertEquals(completed.completedAt(), replay.ledger().completedAt());
        assertEquals(List.of(completed), api.findByRunId("run-1"));
    }

    @Test
    void toolLedgerRejectsDifferentArgumentsForSameToolCall() {
        ToolExecutionLedgerApplicationApi api = toolApi();
        api.claim(new ClaimToolExecutionCommand(
                "run-1", "step-1", "write-file", "call-1", "idem-call-1",
                "{\"path\":\"a.txt\"}", "sha256:a", 60));

        var conflict = api.claim(new ClaimToolExecutionCommand(
                "run-1", "step-1", "write-file", "call-1", "idem-call-1",
                "{\"path\":\"b.txt\"}", "sha256:b", 60));

        assertEquals(ToolExecutionClaimDecisionType.CONFLICT, conflict.type());
        assertEquals("sha256:a", conflict.expectedInputHash());
        assertEquals("sha256:b", conflict.actualInputHash());
    }

    @Test
    void runningToolLedgerEntryIsMarkedUnknownInsteadOfReplayed() {
        ToolExecutionLedgerApplicationApi api = toolApi();
        var claim = api.claim(new ClaimToolExecutionCommand(
                "run-1", "step-1", "write-file", "call-1", "idem-call-1",
                "{\"path\":\"README.md\"}", "sha256:arguments", 60));

        var transition = api.markUnknown(
                new MarkToolExecutionUnknownCommand(
                        "run-1", "call-1", claim.claimToken(), claim.revision(),
                        "crash before terminal ledger write"));

        assertEquals(ToolExecutionTransitionType.APPLIED, transition.type());
        ToolExecutionLedgerView unknown = transition.ledger();
        assertEquals(ToolExecutionStatus.UNKNOWN, unknown.status());
        assertEquals("crash before terminal ledger write", unknown.error());
    }

    @Test
    void conversationSnapshotPersistsCompactedContext() {
        ConversationContextSnapshotApplicationApi api = snapshotApi();
        ConversationContextSnapshotView snapshot = api.save(
                new SaveConversationContextSnapshotCommand(
                        "conversation-1", 3, "compacted context", 1, 12,
                        900, "sha256:abc"));

        assertEquals(3, snapshot.version());
        assertEquals("compacted context", snapshot.summary());
        assertEquals(snapshot, api.findById(snapshot.id()).orElseThrow());
        ConversationContextSnapshotView latest = api.save(
                new SaveConversationContextSnapshotCommand(
                        "conversation-1", 4, "latest context", 1, 14,
                        950, "sha256:def"));
        assertEquals(latest, api.findLatestByConversationId("conversation-1").orElseThrow());
        assertEquals(latest, api.save(new SaveConversationContextSnapshotCommand(
                "conversation-1", 4, "latest context", 1, 14,
                950, "sha256:def")));
        assertThrows(IllegalStateException.class, () -> api.save(
                new SaveConversationContextSnapshotCommand(
                        "conversation-1", 4, "conflicting context", 1, 14,
                        950, "sha256:other")));
    }

    @Test
    void typedHandoffCanBeCreatedAcceptedAndCompleted() {
        RuntimeApplicationApi api = runtimeApi();
        AgentRunView source = api.startRun(new StartAgentRunCommand(
                "owner-1", "agent-1", "version-1", "conversation-1", "project-1", "task-1"));
        api.markRunInProgress(source.id());

        HandoffView handoff = api.createHandoff(new CreateHandoffCommand(
                source.id(),
                new HandoffSnapshot(
                        "finish release",
                        "tests green",
                        List.of("implemented feature"),
                        List.of("use typed handoff"),
                        List.of(),
                        List.of("README.md"),
                        HandoffTestStatus.PASSED,
                        List.of(),
                        List.of("create pull request"))));

        assertEquals(HandoffState.PENDING, handoff.state());
        HandoffView accepted = api.acceptHandoff(new AcceptHandoffCommand(
                handoff.id(), "target-run"));
        assertEquals(HandoffState.ACCEPTED, accepted.state());
        assertEquals("target-run", accepted.targetAgentRunId());

        HandoffView completed = api.completeHandoff(new CompleteHandoffCommand(handoff.id()));
        assertEquals(HandoffState.COMPLETED, completed.state());
        assertEquals(List.of(completed), api.findHandoffsBySourceRun(source.id()));
    }

    @Test
    void projectHandoffTerminalizesSourceRunWithoutCompletingItsWork() {
        RuntimeApplicationApi api = runtimeApi();
        AgentRunView source = api.startRun(new StartAgentRunCommand(
                "owner-1", "agent-1", "version-1", "conversation-1", null, null));
        api.markRunInProgress(source.id());

        AgentRunView handedOff = api.markRunHandedOff(source.id(), "handoff-1");

        assertEquals(AgentRunState.CANCELLED, handedOff.state());
        assertEquals("handoff:handoff-1", handedOff.failureReason());
        assertEquals(handedOff, api.markRunHandedOff(source.id(), "handoff-1"));
        assertThrows(RuntimeException.class,
                () -> api.markRunHandedOff(source.id(), "handoff-2"));
    }

    private RuntimeApplicationApi runtimeApi() {
        return runtimeApi(new InMemoryRuntimeLedgerRepository(fixedTimeProvider()));
    }

    private RuntimeApplicationApi runtimeApi(InMemoryRuntimeLedgerRepository repository) {
        ToolExecutionLedgerApplicationApi toolLedger = toolApi();
        return new RuntimeApplicationService(
                repository,
                toolLedger,
                OBJECT_MAPPER,
                sequentialIdGenerator("run"),
                fixedTimeProvider());
    }

    private ToolExecutionLedgerApplicationApi toolApi() {
        return new ToolExecutionLedgerService(
                new InMemoryToolExecutionLedgerRepository(),
                sequentialIdGenerator("tool"),
                fixedTimeProvider());
    }

    private ConversationContextSnapshotApplicationApi snapshotApi() {
        return new ConversationContextSnapshotService(
                new InMemoryConversationContextSnapshotRepository(),
                sequentialIdGenerator("snapshot"),
                fixedTimeProvider());
    }

    private IdGenerator sequentialIdGenerator(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return () -> prefix + "-" + sequence.incrementAndGet();
    }

    private TimeProvider fixedTimeProvider() {
        return () -> NOW;
    }
}
