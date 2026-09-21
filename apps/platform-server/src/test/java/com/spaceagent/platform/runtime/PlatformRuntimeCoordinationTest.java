package com.spaceagent.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.runtime.api.ResumeAgentRunCommand;
import com.spaceagent.platform.runtime.api.CompleteAgentRunFencedCommand;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeCoordinationApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeContinuationHandler;
import com.spaceagent.platform.runtime.api.StartAgentRunCommand;
import com.spaceagent.platform.runtime.application.RuntimeApplicationService;
import com.spaceagent.platform.runtime.application.RuntimeContinuationWorker;
import com.spaceagent.platform.runtime.application.RuntimeCoordinationApplicationService;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.RunWorkerLeaseClaimType;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationState;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationType;
import com.spaceagent.platform.runtime.infrastructure.RuntimeCoordinationProperties;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryRuntimeLedgerRepository;
import com.spaceagent.platform.tooling.application.ToolExecutionLedgerService;
import com.spaceagent.platform.tooling.infrastructure.memory.InMemoryToolExecutionLedgerRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformRuntimeCoordinationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MutableTime time;
    private InMemoryRuntimeLedgerRepository repository;
    private RuntimeApplicationApi runtime;
    private RuntimeCoordinationApplicationApi coordination;

    @BeforeEach
    void setUp() {
        time = new MutableTime(Instant.parse("2026-08-23T12:00:00Z"));
        repository = new InMemoryRuntimeLedgerRepository(time);
        IdGenerator ids = () -> UUID.randomUUID().toString();
        runtime = new RuntimeApplicationService(
                repository,
                new ToolExecutionLedgerService(
                        new InMemoryToolExecutionLedgerRepository(), ids, time),
                objectMapper,
                ids,
                time);
        coordination = new RuntimeCoordinationApplicationService(
                repository, ids, time, objectMapper);
    }

    @Test
    void expiredLeaseIsReclaimedWithHigherFenceAndStaleWorkerCannotResume() {
        var run = startRun();
        var first = coordination.acquireLease(
                new RuntimeCoordinationApplicationApi.AcquireLeaseCommand(
                        run.id(), "worker-a", 10));
        assertThat(first.type()).isEqualTo(RunWorkerLeaseClaimType.ACQUIRED);
        runtime.resumeFenced(new ResumeAgentRunCommand(
                run.id(), first.lease().leaseToken(), first.lease().fencingToken()));
        runtime.markRunWaitingForUser(run.id());

        var busy = coordination.acquireLease(
                new RuntimeCoordinationApplicationApi.AcquireLeaseCommand(
                        run.id(), "worker-b", 10));
        assertThat(busy.type()).isEqualTo(RunWorkerLeaseClaimType.BUSY);

        time.advanceSeconds(11);
        var reclaimed = coordination.acquireLease(
                new RuntimeCoordinationApplicationApi.AcquireLeaseCommand(
                        run.id(), "worker-b", 10));
        assertThat(reclaimed.type()).isEqualTo(RunWorkerLeaseClaimType.ACQUIRED);
        assertThat(reclaimed.lease().fencingToken())
                .isGreaterThan(first.lease().fencingToken());

        assertThatThrownBy(() -> runtime.resumeFenced(new ResumeAgentRunCommand(
                run.id(), first.lease().leaseToken(), first.lease().fencingToken())))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("RUNTIME_FENCE_REJECTED"));
        assertThat(runtime.resumeFenced(new ResumeAgentRunCommand(
                run.id(), reclaimed.lease().leaseToken(),
                reclaimed.lease().fencingToken())).state())
                .isEqualTo(AgentRunState.IN_PROGRESS);
    }

    @Test
    void continuationWorkerResumesRunAndDurableEventCursorReplaysExclusively() {
        var run = startRun();
        runtime.markRunInProgress(run.id());
        runtime.markRunWaitingForUser(run.id());
        var first = coordination.enqueue(
                new RuntimeCoordinationApplicationApi.EnqueueContinuationCommand(
                        run.id(), RuntimeContinuationType.RESUME_RUN, "resume:one", "{}",
                        null, 3));
        var duplicate = coordination.enqueue(
                new RuntimeCoordinationApplicationApi.EnqueueContinuationCommand(
                        run.id(), RuntimeContinuationType.RESUME_RUN, "resume:one", "{}",
                        null, 3));
        assertThat(duplicate.id()).isEqualTo(first.id());

        RuntimeCoordinationProperties properties = new RuntimeCoordinationProperties();
        properties.setWorkerId("worker-a");
        properties.setLeaseSeconds(10);
        properties.setRetryDelaySeconds(1);
        RuntimeContinuationWorker worker = new RuntimeContinuationWorker(
                coordination, runtime, properties);
        assertThat(worker.processOne()).isTrue();

        assertThat(runtime.findRun(run.id()).orElseThrow().state())
                .isEqualTo(AgentRunState.IN_PROGRESS);
        assertThat(coordination.find(first.id()).orElseThrow().state())
                .isEqualTo(RuntimeContinuationState.COMPLETED);

        var firstPage = runtime.findEventsAfter(run.id(), -1, 3);
        var secondPage = runtime.findEventsAfter(
                run.id(), firstPage.nextSequence(), 500);
        assertThat(firstPage.events()).hasSize(3);
        assertThat(secondPage.events())
                .allMatch(event -> event.sequence() > firstPage.nextSequence());
        assertThat(runtime.findEvents(run.id()))
                .extracting(event -> event.sequence())
                .containsExactlyElementsOf(java.util.stream.LongStream.range(
                                0, runtime.findEvents(run.id()).size())
                        .boxed().toList());
    }

    @Test
    void automationHandlerRunsUnderLeaseAndCompletesDispatchRunFenced() {
        var run = startRun();
        var continuation = coordination.enqueue(
                new RuntimeCoordinationApplicationApi.EnqueueContinuationCommand(
                        run.id(), RuntimeContinuationType.AUTOMATION_EXECUTION,
                        "automation:one", "{\"executionId\":\"execution\"}", null, 1));
        AtomicInteger handled = new AtomicInteger();
        RuntimeContinuationHandler handler = new RuntimeContinuationHandler() {
            @Override
            public RuntimeContinuationType type() {
                return RuntimeContinuationType.AUTOMATION_EXECUTION;
            }

            @Override
            public void handle(ContinuationHandlerContext context) {
                assertThat(context.continuation().id()).isEqualTo(continuation.id());
                assertThat(context.lease().fencingToken()).isPositive();
                handled.incrementAndGet();
            }
        };
        RuntimeCoordinationProperties properties = new RuntimeCoordinationProperties();
        properties.setWorkerId("automation-worker");
        properties.setLeaseSeconds(10);
        RuntimeContinuationWorker worker = new RuntimeContinuationWorker(
                coordination, runtime, properties, List.of(handler));

        assertThat(worker.processOne()).isTrue();
        assertThat(handled).hasValue(1);
        assertThat(runtime.findRun(run.id()).orElseThrow().state())
                .isEqualTo(AgentRunState.COMPLETED);
        assertThat(coordination.find(continuation.id()).orElseThrow().state())
                .isEqualTo(RuntimeContinuationState.COMPLETED);
    }

    @Test
    void staleFenceCannotCompleteRunAfterLeaseReclaim() {
        var run = startRun();
        var first = coordination.acquireLease(
                new RuntimeCoordinationApplicationApi.AcquireLeaseCommand(
                        run.id(), "worker-a", 10));
        runtime.resumeFenced(new ResumeAgentRunCommand(
                run.id(), first.lease().leaseToken(), first.lease().fencingToken()));
        time.advanceSeconds(11);
        var reclaimed = coordination.acquireLease(
                new RuntimeCoordinationApplicationApi.AcquireLeaseCommand(
                        run.id(), "worker-b", 10));

        assertThatThrownBy(() -> runtime.completeFenced(new CompleteAgentRunFencedCommand(
                run.id(), first.lease().leaseToken(), first.lease().fencingToken())))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("RUNTIME_FENCE_REJECTED"));
        assertThat(runtime.completeFenced(new CompleteAgentRunFencedCommand(
                run.id(), reclaimed.lease().leaseToken(), reclaimed.lease().fencingToken())).state())
                .isEqualTo(AgentRunState.COMPLETED);
    }

    @Test
    void staleFenceCannotCancelRunAfterLeaseReclaim() {
        var run = startRun();
        var first = coordination.acquireLease(
                new RuntimeCoordinationApplicationApi.AcquireLeaseCommand(
                        run.id(), "worker-a", 10));
        runtime.resumeFenced(new ResumeAgentRunCommand(
                run.id(), first.lease().leaseToken(), first.lease().fencingToken()));
        time.advanceSeconds(11);
        var reclaimed = coordination.acquireLease(
                new RuntimeCoordinationApplicationApi.AcquireLeaseCommand(
                        run.id(), "worker-b", 10));

        assertThatThrownBy(() -> runtime.cancelFenced(
                new com.spaceagent.platform.runtime.api.CancelAgentRunFencedCommand(
                        run.id(), first.lease().leaseToken(), first.lease().fencingToken(), "stale")))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("RUNTIME_FENCE_REJECTED"));
        assertThat(runtime.cancelFenced(
                new com.spaceagent.platform.runtime.api.CancelAgentRunFencedCommand(
                        run.id(), reclaimed.lease().leaseToken(),
                        reclaimed.lease().fencingToken(), "cancelled")).state())
                .isEqualTo(AgentRunState.CANCELLED);
    }

    @Test
    void expiredContinuationClaimCanBeReclaimedAndOldCompletionIsRejected() {
        var run = startRun();
        var continuation = coordination.enqueue(
                new RuntimeCoordinationApplicationApi.EnqueueContinuationCommand(
                        run.id(), RuntimeContinuationType.RESUME_RUN, "resume:reclaim", "{}",
                        null, 3));
        var first = coordination.claimNext(
                new RuntimeCoordinationApplicationApi.ClaimNextContinuationCommand(
                        "worker-a", 10)).orElseThrow();
        time.advanceSeconds(11);
        var reclaimed = coordination.claimNext(
                new RuntimeCoordinationApplicationApi.ClaimNextContinuationCommand(
                        "worker-b", 10)).orElseThrow();

        assertThat(reclaimed.continuation().id()).isEqualTo(continuation.id());
        assertThat(reclaimed.continuation().attempt()).isEqualTo(2);
        assertThat(reclaimed.lease().fencingToken())
                .isGreaterThan(first.lease().fencingToken());
        assertThatThrownBy(() -> coordination.complete(
                new RuntimeCoordinationApplicationApi.CompleteContinuationCommand(
                        continuation.id(), "worker-a", first.lease().leaseToken(),
                        first.lease().fencingToken())))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("RUNTIME_FENCE_REJECTED"));
        assertThat(coordination.complete(
                new RuntimeCoordinationApplicationApi.CompleteContinuationCommand(
                        continuation.id(), "worker-b", reclaimed.lease().leaseToken(),
                        reclaimed.lease().fencingToken())).state())
                .isEqualTo(RuntimeContinuationState.COMPLETED);
    }

    private com.spaceagent.platform.runtime.api.AgentRunView startRun() {
        return runtime.startRun(new StartAgentRunCommand(
                "owner", "agent", "version", "conversation", null, null));
    }

    private static final class MutableTime implements TimeProvider {
        private Instant now;

        private MutableTime(Instant now) {
            this.now = now;
        }

        @Override
        public Instant now() {
            return now;
        }

        private void advanceSeconds(long seconds) {
            now = now.plus(seconds, ChronoUnit.SECONDS);
        }
    }
}
