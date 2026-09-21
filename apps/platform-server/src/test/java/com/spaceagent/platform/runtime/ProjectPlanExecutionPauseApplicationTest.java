package com.spaceagent.platform.runtime;

import com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi;
import com.spaceagent.platform.runtime.application.ProjectPlanExecutionApplicationService;
import com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState;
import com.spaceagent.platform.runtime.domain.ProjectPlanExecutionDesiredState;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryProjectCodingJobRepository;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryProjectPlanExecutionRepository;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectPlanExecutionPauseApplicationTest {
    @Test
    void blockedPausePreservesDesiredStateAndStableControlEvidence() {
        var executionRepository = new InMemoryProjectPlanExecutionRepository();
        var service = new ProjectPlanExecutionApplicationService(
                executionRepository, new InMemoryProjectCodingJobRepository(),
                () -> java.time.Instant.parse("2026-09-07T00:00:00Z"));
        Fixture fixture = new Fixture();
        var started = service.start(fixture.startCommand());
        var pausing = service.requestPause(new ProjectPlanExecutionApplicationApi.PauseCommand(
                fixture.tenantId, fixture.ownerId, fixture.projectId, fixture.taskPlanId,
                started.id(), started.revision(), "maintenance"));

        var blocked = service.block(fixture.transition(started.id()),
                "PROJECT_PLAN_EXECUTION_UNKNOWN_EFFECT");
        var persisted = executionRepository.findById(started.id()).orElseThrow();

        assertThat(blocked.state()).isEqualTo(ProjectPlanExecutionState.BLOCKED);
        assertThat(persisted.desiredState()).isEqualTo(ProjectPlanExecutionDesiredState.PAUSED);
        assertThat(persisted.controlReason())
                .isEqualTo("PROJECT_PLAN_EXECUTION_UNKNOWN_EFFECT");
        assertThat(persisted.revision()).isEqualTo(pausing.revision() + 1);
        assertThat(persisted.completedAt()).isNotNull();
        assertThatThrownBy(() -> service.requestPause(
                new ProjectPlanExecutionApplicationApi.PauseCommand(
                        fixture.tenantId, fixture.ownerId, fixture.projectId,
                        fixture.taskPlanId, started.id(), persisted.revision(), "again")))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode())
                                .isEqualTo("PROJECT_PLAN_EXECUTION_RECONCILIATION_REQUIRED"));
    }

    @Test
    void pauseUsesExpectedRevisionIsIdempotentAndProjectsPausedAsActive() {
        var executionRepository = new InMemoryProjectPlanExecutionRepository();
        var service = new ProjectPlanExecutionApplicationService(
                executionRepository, new InMemoryProjectCodingJobRepository(),
                () -> java.time.Instant.parse("2026-09-07T00:00:00Z"));
        Fixture fixture = new Fixture();
        var started = service.start(fixture.startCommand());
        var pause = service.requestPause(new ProjectPlanExecutionApplicationApi.PauseCommand(
                fixture.tenantId, fixture.ownerId, fixture.projectId, fixture.taskPlanId,
                started.id(), started.revision(), "maintenance"));

        assertThat(pause.state()).isEqualTo(ProjectPlanExecutionState.PAUSING);
        assertThat(pause.revision()).isEqualTo(2);
        assertThat(service.getActiveByTaskPlan(new ProjectPlanExecutionApplicationApi.QueryByTaskPlan(
                fixture.tenantId, fixture.ownerId, fixture.taskPlanId))).isPresent();

        var replay = service.requestPause(new ProjectPlanExecutionApplicationApi.PauseCommand(
                fixture.tenantId, fixture.ownerId, fixture.projectId, fixture.taskPlanId,
                started.id(), pause.revision(), null));
        assertThat(replay.revision()).isEqualTo(pause.revision());
        assertThat(replay.state()).isEqualTo(ProjectPlanExecutionState.PAUSING);

        assertThatThrownBy(() -> service.requestPause(new ProjectPlanExecutionApplicationApi.PauseCommand(
                fixture.tenantId, fixture.ownerId, fixture.projectId, fixture.taskPlanId,
                started.id(), started.revision(), "maintenance")))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("PROJECT_PLAN_EXECUTION_STALE_REVISION"));

        var transition = fixture.transition(started.id());
        var paused = service.acknowledgePause(transition);
        assertThat(paused.state()).isEqualTo(ProjectPlanExecutionState.PAUSED);
        assertThat(paused.revision()).isEqualTo(3);
        assertThat(service.begin(transition).state()).isEqualTo(ProjectPlanExecutionState.PAUSED);

        var resumed = service.resume(new ProjectPlanExecutionApplicationApi.ResumeCommand(
                fixture.tenantId, fixture.ownerId, fixture.projectId, fixture.taskPlanId,
                started.id(), paused.revision()));
        assertThat(resumed.state()).isEqualTo(ProjectPlanExecutionState.RUNNING);
        assertThat(resumed.revision()).isEqualTo(4);
        assertThatThrownBy(() -> service.resume(new ProjectPlanExecutionApplicationApi.ResumeCommand(
                fixture.tenantId, fixture.ownerId, fixture.projectId, fixture.taskPlanId,
                started.id(), paused.revision())))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("PROJECT_PLAN_EXECUTION_STALE_REVISION"));

        var cancelling = service.requestCancel(new ProjectPlanExecutionApplicationApi.CancelCommand(
                fixture.tenantId, fixture.ownerId, fixture.projectId, fixture.taskPlanId,
                started.id(), resumed.revision(), "stop requested"));
        assertThat(cancelling.state()).isEqualTo(ProjectPlanExecutionState.CANCELLING);
        assertThat(service.controlTransitions(10))
                .extracting(ProjectPlanExecutionApplicationApi.ExecutionView::id)
                .containsExactly(started.id());
        var cancelled = service.acknowledgeCancel(transition);
        assertThat(cancelled.state()).isEqualTo(ProjectPlanExecutionState.CANCELLED);
        assertThat(cancelled.completedAt()).isNotNull();
        assertThat(service.controlTransitions(10)).isEmpty();
    }

    private static final class Fixture {
        private final String tenantId = id();
        private final String ownerId = id();
        private final String projectId = id();
        private final String directoryId = id();
        private final String conversationId = id();
        private final String sourceId = id();
        private final String rootTaskId = id();
        private final String taskPlanId = id();
        private final String agentId = id();
        private final String agentVersionId = id();
        private final String reviewerVersionId = id();

        private ProjectPlanExecutionApplicationApi.StartCommand startCommand() {
            return new ProjectPlanExecutionApplicationApi.StartCommand(
                    id(), tenantId, ownerId, projectId, directoryId, conversationId, sourceId,
                    rootTaskId, taskPlanId, agentId, agentVersionId, reviewerVersionId, "main",
                    "pause-execution-001");
        }

        private ProjectPlanExecutionApplicationApi.TransitionCommand transition(String executionId) {
            return new ProjectPlanExecutionApplicationApi.TransitionCommand(
                    tenantId, ownerId, projectId, taskPlanId, executionId);
        }

        private static String id() {
            return UUID.randomUUID().toString();
        }
    }
}
