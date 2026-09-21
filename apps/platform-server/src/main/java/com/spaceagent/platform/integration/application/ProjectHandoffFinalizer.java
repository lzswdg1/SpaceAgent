package com.spaceagent.platform.integration.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.memory.api.MemoryApplicationApi;
import com.spaceagent.platform.memory.api.SaveProjectMemorySnapshotCommand;
import com.spaceagent.platform.memory.domain.MemoryKind;
import com.spaceagent.platform.project.api.WorkspaceApplicationApi;
import com.spaceagent.platform.project.domain.WorkspaceState;
import com.spaceagent.platform.runtime.api.ProjectCodingJobApplicationApi;
import com.spaceagent.platform.runtime.api.ProjectRunHandoffApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.ProjectCodingJobState;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Map;

/** Finalizes completed handoffs without holding a database lock over Git cleanup. */
@Service
public class ProjectHandoffFinalizer {
    private final ProjectRunHandoffApplicationApi handoffs;
    private final ProjectCodingJobApplicationApi jobs;
    private final RuntimeApplicationApi runtime;
    private final WorkspaceApplicationApi workspaces;
    private final MemoryApplicationApi memory;
    private final ObjectMapper json;

    public ProjectHandoffFinalizer(
            ProjectRunHandoffApplicationApi handoffs,
            ProjectCodingJobApplicationApi jobs,
            RuntimeApplicationApi runtime,
            WorkspaceApplicationApi workspaces,
            MemoryApplicationApi memory,
            ObjectMapper json) {
        this.handoffs = handoffs;
        this.jobs = jobs;
        this.runtime = runtime;
        this.workspaces = workspaces;
        this.memory = memory;
        this.json = json;
    }

    public boolean runOnce(String workerId, int leaseSeconds, int maximumAttempts) {
        var claim = handoffs.claimFinalization(workerId, leaseSeconds, maximumAttempts).orElse(null);
        if (claim == null) return false;
        var command = new ProjectRunHandoffApplicationApi.FinalizationCommand(
                claim.handoff().id(), workerId, claim.claimToken(), claim.fencingToken());
        try {
            var handoff = claim.handoff();
            var job = jobs.get(new ProjectCodingJobApplicationApi.Query(
                    handoff.tenantId(), handoff.ownerId(), handoff.projectId(),
                    handoff.taskPlanId(), handoff.planStepId(), handoff.targetCodingJobId()));
            if (job.state() != ProjectCodingJobState.COMPLETED || job.sourceMergeId() == null) {
                throw conflict("PROJECT_HANDOFF_TARGET_NOT_COMPLETE");
            }
            var run = runtime.findRun(handoff.targetAgentRunId())
                    .filter(value -> value.state() == AgentRunState.COMPLETED)
                    .orElseThrow(() -> conflict("PROJECT_HANDOFF_TARGET_RUN_NOT_COMPLETE"));
            if (!handoff.workspaceId().equals(run.workspaceId())) {
                throw conflict("PROJECT_HANDOFF_TARGET_WORKSPACE_MISMATCH");
            }
            handoffs.heartbeat(command, leaseSeconds);
            String memoryKey = "handoff-completion:" + handoff.id();
            memory.saveProjectSnapshot(new SaveProjectMemorySnapshotCommand(
                    handoff.projectId(), MemoryKind.PROCEDURE, memoryKey,
                    completionMemory(handoff, job)));
            var workspace = workspaces.get(new WorkspaceApplicationApi.Query(
                    handoff.tenantId(), handoff.ownerId(), handoff.projectId(), handoff.workspaceId()));
            if (workspace.state() != WorkspaceState.ARCHIVED) {
                workspaces.archive(new WorkspaceApplicationApi.ArchiveCommand(
                        handoff.tenantId(), handoff.ownerId(), handoff.projectId(),
                        handoff.workspaceId()));
            }
            handoffs.complete(command, memoryKey);
            return true;
        } catch (RuntimeException error) {
            String code = error instanceof BusinessException business
                    ? safe(business.getCode()) : "PROJECT_HANDOFF_FINALIZATION_FAILED";
            handoffs.fail(command, code, claim.handoff().attempt() >= maximumAttempts);
            return true;
        }
    }

    private String completionMemory(
            ProjectRunHandoffApplicationApi.HandoffView handoff,
            ProjectCodingJobApplicationApi.JobView job) {
        try {
            return json.writeValueAsString(Map.ofEntries(
                    Map.entry("handoffId", handoff.id()),
                    Map.entry("recoverySnapshotId", handoff.recoverySnapshotId()),
                    Map.entry("sourceAgentRunId", handoff.sourceAgentRunId()),
                    Map.entry("targetAgentRunId", handoff.targetAgentRunId()),
                    Map.entry("targetAgentId", handoff.targetAgentId()),
                    Map.entry("taskPlanId", handoff.taskPlanId()),
                    Map.entry("planStepId", handoff.planStepId()),
                    Map.entry("patchArtifactId", job.patchArtifactId()),
                    Map.entry("commitArtifactId", job.commitArtifactId()),
                    Map.entry("reviewId", job.reviewId()),
                    Map.entry("sourceMergeId", job.sourceMergeId()),
                    Map.entry("workspaceArchived", true)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize Project completion memory", error);
        }
    }

    private static String safe(String value) {
        return value != null && value.matches("[A-Z0-9_]{1,120}")
                ? value : "PROJECT_HANDOFF_FINALIZATION_FAILED";
    }

    private static BusinessException conflict(String code) {
        return new BusinessException("Project handoff finalization conflict", HttpStatus.CONFLICT, code);
    }
}
