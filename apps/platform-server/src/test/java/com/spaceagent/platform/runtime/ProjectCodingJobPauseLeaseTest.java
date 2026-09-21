package com.spaceagent.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.runtime.api.ProjectCodingJobApplicationApi;
import com.spaceagent.platform.runtime.application.ProjectCodingJobApplicationService;
import com.spaceagent.platform.runtime.domain.ProjectCodingJobState;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryProjectCodingJobRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectCodingJobPauseLeaseTest {
    @Test
    void releaseForPauseReturnsClaimedJobToPendingAndAllowsReclaim() {
        var service = new ProjectCodingJobApplicationService(
                new InMemoryProjectCodingJobRepository(), new UuidGenerator(),
                () -> Instant.parse("2026-09-07T00:00:00Z"), new ObjectMapper());
        String projectId = id();
        var queued = service.enqueue(new ProjectCodingJobApplicationApi.EnqueueCommand(
                "tenant", "owner", projectId, id(), id(), id(), id(), id(), id(), id(), id(),
                id(), id(), id(), "main", "pause-lease-001"));
        var claim = service.claim("worker", 900, 3).orElseThrow();

        var released = service.releaseForPause(new ProjectCodingJobApplicationApi.ClaimCommand(
                claim.job().id(), "worker", claim.claimToken(), claim.fencingToken()));

        assertThat(released.id()).isEqualTo(queued.id());
        assertThat(released.state()).isEqualTo(ProjectCodingJobState.PENDING);
        assertThat(released.revision()).isEqualTo(claim.job().revision() + 1);
        assertThat(service.claim("worker-2", 900, 3)).isPresent();
    }

    @Test
    void pendingCancelIsTerminalWhileAnActiveLeaseIsRejected() {
        var service = new ProjectCodingJobApplicationService(
                new InMemoryProjectCodingJobRepository(), new UuidGenerator(),
                () -> Instant.parse("2026-09-08T00:00:00Z"), new ObjectMapper());
        String projectId = id(), taskPlanId = id(), planStepId = id();
        var queued = service.enqueue(new ProjectCodingJobApplicationApi.EnqueueCommand(
                "tenant", "owner", projectId, id(), id(), id(), id(), id(), taskPlanId, id(),
                planStepId, id(), id(), id(), "main", "cancel-pending-001"));
        var cancelled = service.cancel(new ProjectCodingJobApplicationApi.CancelCommand(
                "tenant", "owner", projectId, taskPlanId, planStepId, queued.id(),
                queued.revision(), "stop"));
        assertThat(cancelled.state()).isEqualTo(ProjectCodingJobState.FAILED);
        assertThat(cancelled.safeErrorCode()).isEqualTo("PROJECT_PLAN_EXECUTION_CANCELLED");
        assertThat(cancelled.completedAt()).isNotNull();

        var second = service.enqueue(new ProjectCodingJobApplicationApi.EnqueueCommand(
                "tenant", "owner", projectId, id(), id(), id(), id(), id(), id(), id(), id(),
                id(), id(), id(), "main", "cancel-running-002"));
        var active = service.claim("worker", 900, 3).orElseThrow();
        assertThat(active.job().id()).isEqualTo(second.id());
        assertThatThrownBy(() -> service.cancel(new ProjectCodingJobApplicationApi.CancelCommand(
                active.job().tenantId(), active.job().ownerId(), active.job().projectId(),
                active.job().taskPlanId(), active.job().planStepId(), active.job().id(),
                active.job().revision(), "stop")))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("PROJECT_CODING_CANCEL_LEASE_ACTIVE"));
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
