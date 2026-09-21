package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.integration.application.ProjectHandoffFinalizer;
import com.spaceagent.platform.memory.api.MemoryApplicationApi;
import com.spaceagent.platform.project.api.WorkspaceApplicationApi;
import com.spaceagent.platform.project.domain.WorkspaceMode;
import com.spaceagent.platform.project.domain.WorkspaceState;
import com.spaceagent.platform.runtime.api.AgentRunView;
import com.spaceagent.platform.runtime.api.ProjectCodingJobApplicationApi;
import com.spaceagent.platform.runtime.api.ProjectRunHandoffApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.ProjectCodingJobState;
import com.spaceagent.platform.runtime.domain.ProjectRunHandoffState;
import com.spaceagent.platform.runtime.domain.orchestration.ExecutionCursor;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectHandoffFinalizerTest {
    @Test
    void consolidatesSafeMemoryAndArchivesCompletedWorkspace() {
        Instant now = Instant.parse("2026-09-06T11:00:00Z");
        var handoffs = mock(ProjectRunHandoffApplicationApi.class);
        var jobs = mock(ProjectCodingJobApplicationApi.class);
        var runtime = mock(RuntimeApplicationApi.class);
        var workspaces = mock(WorkspaceApplicationApi.class);
        var memory = mock(MemoryApplicationApi.class);
        var finalizer = new ProjectHandoffFinalizer(
                handoffs, jobs, runtime, workspaces, memory, new ObjectMapper());
        String handoffId = id(), targetRun = id(), workspace = id();
        var handoff = handoff(handoffId, targetRun, workspace, now);
        when(handoffs.claimFinalization("worker", 60, 3)).thenReturn(Optional.of(
                new ProjectRunHandoffApplicationApi.FinalizationClaim(
                        handoff, "claim-token", 4, now.plusSeconds(60))));
        when(jobs.get(any())).thenReturn(job(handoff, now));
        when(runtime.findRun(targetRun)).thenReturn(Optional.of(new AgentRunView(
                targetRun, handoff.targetAgentId(), null,
                handoff.tenantId(), handoff.ownerId(), handoff.targetConversationId(),
                handoff.projectId(), handoff.projectDirectoryId(), workspace, handoff.taskId(),
                handoff.taskPlanId(), handoff.planStepId(), ExecutionCursor.initial(), 4,
                AgentRunState.COMPLETED, null, now, now, now)));
        when(workspaces.get(any())).thenReturn(new WorkspaceApplicationApi.WorkspaceView(
                workspace, handoff.projectId(), handoff.projectDirectoryId(), handoff.taskId(),
                handoff.sourceRepositoryId(), null, "plan-step:" + handoff.planStepId(),
                WorkspaceMode.MANAGED_GIT, id(), "main", "branch", "managed:" + workspace,
                "a".repeat(40), true, WorkspaceState.READY, null, 1, now, now));

        assertThat(finalizer.runOnce("worker", 60, 3)).isTrue();
        verify(memory).saveProjectSnapshot(any());
        verify(workspaces).archive(any());
        verify(handoffs).complete(any(), eq("handoff-completion:" + handoffId));
    }

    private static ProjectRunHandoffApplicationApi.HandoffView handoff(
            String id, String targetRun, String workspace, Instant now) {
        return new ProjectRunHandoffApplicationApi.HandoffView(
                id, "tenant", "owner", uuid(), uuid(), uuid(), uuid(), uuid(), uuid(), uuid(),
                "main", uuid(), uuid(), workspace, uuid(), "sha256:" + "a".repeat(64),
                uuid(), "target-agent", "reviewer-agent", uuid(), targetRun,
                ProjectRunHandoffState.FINALIZING, null, null, 1, 3, now, now, null);
    }

    private static ProjectCodingJobApplicationApi.JobView job(
            ProjectRunHandoffApplicationApi.HandoffView handoff, Instant now) {
        return new ProjectCodingJobApplicationApi.JobView(
                handoff.targetCodingJobId(), handoff.tenantId(), handoff.ownerId(),
                handoff.projectId(), handoff.projectDirectoryId(), handoff.targetConversationId(),
                handoff.sourceRepositoryId(), handoff.rootTaskId(), handoff.taskId(),
                handoff.taskPlanId(), null, handoff.planStepId(), handoff.targetAgentId(),
                null, null, handoff.baseRef(),
                ProjectCodingJobState.COMPLETED, handoff.workspaceId(), handoff.targetAgentRunId(),
                uuid(), 2, 1, null, null, uuid(), uuid(), uuid(), uuid(), null, 1, 6,
                now, now, now, now, handoff.reviewerAgentId());
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private static String uuid() {
        return id();
    }
}
