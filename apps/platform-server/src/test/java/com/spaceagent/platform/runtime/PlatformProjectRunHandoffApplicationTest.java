package com.spaceagent.platform.runtime;

import com.spaceagent.platform.runtime.api.ProjectRunHandoffApplicationApi;
import com.spaceagent.platform.runtime.application.ProjectRunHandoffApplicationService;
import com.spaceagent.platform.runtime.domain.ProjectRunHandoffState;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryProjectRunHandoffRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformProjectRunHandoffApplicationTest {
    @Test
    void persistsReplayAttachesAndFencesFinalization() {
        Instant now = Instant.parse("2026-09-06T09:00:00Z");
        var service = new ProjectRunHandoffApplicationService(
                new InMemoryProjectRunHandoffRepository(), new UuidGenerator(), () -> now);
        var command = command("handoff-idempotency-001");

        var created = service.create(command);
        assertThat(created.state()).isEqualTo(ProjectRunHandoffState.PENDING);
        assertThat(service.create(command).id()).isEqualTo(created.id());
        assertThatThrownBy(() -> service.create(new ProjectRunHandoffApplicationApi.CreateCommand(
                command.id(), command.tenantId(), command.ownerId(), command.projectId(),
                command.projectDirectoryId(), command.sourceRepositoryId(), command.rootTaskId(),
                command.taskId(), command.taskPlanId(), command.planStepId(), command.baseRef(),
                command.sourceCodingJobId(), command.sourceAgentRunId(), command.workspaceId(),
                command.recoverySnapshotId(), command.recoverySnapshotHash(), id(),
                command.targetAgentId(), command.reviewerAgentId(), command.targetCodingJobId(),
                command.idempotencyKey())))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("PROJECT_HANDOFF_IDEMPOTENCY_CONFLICT"));

        var active = service.attachTargetRun(command.targetCodingJobId(), "target-run");
        assertThat(active.state()).isEqualTo(ProjectRunHandoffState.ACTIVE);
        assertThat(service.attachTargetRun(command.targetCodingJobId(), "target-run").revision())
                .isEqualTo(active.revision());
        assertThat(service.readyForFinalization(command.targetCodingJobId()).state())
                .isEqualTo(ProjectRunHandoffState.READY_TO_FINALIZE);

        var claim = service.claimFinalization("worker-a", 60, 3).orElseThrow();
        assertThat(claim.handoff().state()).isEqualTo(ProjectRunHandoffState.FINALIZING);
        var stale = new ProjectRunHandoffApplicationApi.FinalizationCommand(
                claim.handoff().id(), "worker-b", claim.claimToken(), claim.fencingToken());
        assertThatThrownBy(() -> service.complete(stale, "memory-key"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("PROJECT_HANDOFF_LEASE_LOST"));
        var completed = service.complete(new ProjectRunHandoffApplicationApi.FinalizationCommand(
                claim.handoff().id(), "worker-a", claim.claimToken(), claim.fencingToken()),
                "memory-key");
        assertThat(completed.state()).isEqualTo(ProjectRunHandoffState.COMPLETED);
        assertThat(completed.memoryKey()).isEqualTo("memory-key");
        assertThat(service.list(new ProjectRunHandoffApplicationApi.ListQuery(
                "tenant", "owner", command.projectId(), 1, 20)).total()).isEqualTo(1);
    }

    private static ProjectRunHandoffApplicationApi.CreateCommand command(String key) {
        return new ProjectRunHandoffApplicationApi.CreateCommand(
                id(), "tenant", "owner", id(), id(), id(), id(), id(), id(), id(),
                "main", id(), "source-run", id(), id(), "sha256:" + "a".repeat(64),
                "target-conversation", "target-agent", "reviewer-agent", id(), key);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
