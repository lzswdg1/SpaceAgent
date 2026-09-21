package com.spaceagent.platform.runtime;

import com.spaceagent.platform.runtime.domain.ProjectPlanExecution;
import com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectPlanExecutionDomainTest {

    @Test
    void acceptsReadyExecutionWithStableTimestampState() {
        var now = Instant.parse("2026-09-07T00:00:00Z");
        var execution = new ProjectPlanExecution(
                "execution-id",
                "tenant-id",
                "owner-id",
                "project-id",
                "project-directory-id",
                "conversation-id",
                "source-repository-id",
                "root-task-id",
                "task-plan-id",
                "agent-id",
                "agent-version-id",
                "reviewer-agent-version-id",
                "main",
                "idempotency-hash",
                "input-hash",
                ProjectPlanExecutionState.READY,
                null,
                0,
                1L,
                now,
                null,
                now,
                null);

        assertThat(execution.state()).isEqualTo(ProjectPlanExecutionState.READY);
        assertThat(execution.revision()).isEqualTo(1L);
        assertThat(execution.createdAt()).isEqualTo(now);
    }

    @Test
    void rejectsInvalidLifecycleCounters() {
        var now = Instant.parse("2026-09-07T00:00:00Z");
        assertThatThrownBy(() -> new ProjectPlanExecution(
                "execution-id",
                "tenant-id",
                "owner-id",
                "project-id",
                "project-directory-id",
                "conversation-id",
                "source-repository-id",
                "root-task-id",
                "task-plan-id",
                "agent-id",
                "agent-version-id",
                "reviewer-agent-version-id",
                "main",
                "idempotency-hash",
                "input-hash",
                ProjectPlanExecutionState.RUNNING,
                null,
                -1,
                1L,
                now,
                now,
                now,
                null)).isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ProjectPlanExecution(
                "execution-id",
                "tenant-id",
                "owner-id",
                "project-id",
                "project-directory-id",
                "conversation-id",
                "source-repository-id",
                "root-task-id",
                "task-plan-id",
                "agent-id",
                "agent-version-id",
                "reviewer-agent-version-id",
                "main",
                "idempotency-hash",
                "input-hash",
                ProjectPlanExecutionState.RUNNING,
                null,
                0,
                0L,
                now,
                now,
                now,
                null)).isInstanceOf(IllegalArgumentException.class);
    }
}
