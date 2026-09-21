package com.spaceagent.platform.runtime.api;

import com.spaceagent.platform.artifact.domain.ArtifactType;
import com.spaceagent.platform.governance.domain.ApprovalState;
import com.spaceagent.platform.governance.domain.GovernanceActionType;
import com.spaceagent.platform.inference.domain.ModelCallStatus;
import com.spaceagent.platform.project.domain.PlanStepState;
import com.spaceagent.platform.project.domain.ProjectBlueprintStatus;
import com.spaceagent.platform.project.domain.TaskPlanStatus;
import com.spaceagent.platform.project.domain.TaskState;
import com.spaceagent.platform.project.domain.WorkspaceMode;
import com.spaceagent.platform.project.domain.WorkspaceState;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.RunStepState;
import com.spaceagent.platform.tooling.domain.ToolExecutionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.TreeMap;

/**
 * Owner-scoped creation and lookup of immutable Coding recovery packages.
 *
 * <p>The package is point-in-time evidence. Every mutable lifecycle remains authoritative in its
 * owning module and must be revalidated before a later effect.
 */
public interface ProjectRecoveryApplicationApi {

    CodingRecoveryPackageView capture(CaptureCommand command);

    CodingRecoveryPackageView get(Query query);

    CodingRecoveryPackageView latest(LatestQuery query);

    record CaptureCommand(
            String tenantId,
            String userId,
            String projectId,
            String agentRunId,
            String idempotencyKey) {
    }

    record Query(
            String tenantId,
            String userId,
            String projectId,
            String agentRunId,
            String snapshotId) {
    }

    record LatestQuery(
            String tenantId,
            String userId,
            String projectId,
            String agentRunId) {
    }

    record CodingRecoveryPackageView(
            String snapshotId,
            String snapshotSha256,
            Instant capturedAt,
            RecoveryContext context) {
    }

    record RecoveryContext(
            ProjectContext project,
            BlueprintContext blueprint,
            TaskExecutionContext task,
            WorkspaceGitContext workspace,
            RuntimeContext runtime,
            ConversationContext conversation,
            List<ArtifactEvidence> artifacts,
            List<TestEvidence> tests,
            List<AcceptanceEvidence> acceptance,
            List<ModelCallEvidence> modelCalls,
            List<UnknownToolEffect> unknownToolEffects,
            List<UnknownModelEffect> unknownModelEffects,
            List<ApprovalRequirement> approvals,
            String nextAction,
            List<String> blockers) {

        public RecoveryContext {
            artifacts = List.copyOf(artifacts);
            tests = List.copyOf(tests);
            acceptance = List.copyOf(acceptance);
            modelCalls = List.copyOf(modelCalls);
            unknownToolEffects = List.copyOf(unknownToolEffects);
            unknownModelEffects = List.copyOf(unknownModelEffects);
            approvals = List.copyOf(approvals);
            blockers = List.copyOf(blockers);
        }
    }

    record ProjectContext(
            String projectId,
            String projectDirectoryId,
            String directoryName,
            String directoryRelativePath,
            String sourceRepositoryId) {
    }

    record BlueprintContext(
            String id,
            int version,
            ProjectBlueprintStatus status,
            String goal,
            List<String> requirements,
            List<String> modules,
            List<String> boundaries,
            List<String> architectureDecisions,
            Map<String, String> commands,
            List<String> environmentRefs,
            List<String> conventions,
            List<String> forbiddenAreas,
            List<String> risks,
            List<String> openQuestions,
            List<String> acceptanceCriteria,
            List<String> evidence) {

        public BlueprintContext {
            requirements = List.copyOf(requirements);
            modules = List.copyOf(modules);
            boundaries = List.copyOf(boundaries);
            architectureDecisions = List.copyOf(architectureDecisions);
            commands = Collections.unmodifiableMap(new TreeMap<>(commands));
            environmentRefs = List.copyOf(environmentRefs);
            conventions = List.copyOf(conventions);
            forbiddenAreas = List.copyOf(forbiddenAreas);
            risks = List.copyOf(risks);
            openQuestions = List.copyOf(openQuestions);
            acceptanceCriteria = List.copyOf(acceptanceCriteria);
            evidence = List.copyOf(evidence);
        }
    }

    record TaskExecutionContext(
            String rootTaskId,
            String rootTitle,
            String rootGoal,
            TaskState rootState,
            String rootCurrentTaskPlanId,
            String taskId,
            String parentTaskId,
            String title,
            String goal,
            String description,
            List<String> constraints,
            List<String> acceptanceCriteria,
            TaskState taskState,
            String taskPlanId,
            int taskPlanVersion,
            TaskPlanStatus taskPlanStatus,
            String planStepId,
            String stepKey,
            int stepSequence,
            PlanStepState planStepState,
            List<String> dependencyStepIds,
            String requiredCapability,
            String preferredAgentId,
            String expectedOutput,
            List<String> stepAcceptanceCriteria,
            boolean approvalRequired) {

        public TaskExecutionContext {
            constraints = List.copyOf(constraints);
            acceptanceCriteria = List.copyOf(acceptanceCriteria);
            dependencyStepIds = List.copyOf(dependencyStepIds);
            stepAcceptanceCriteria = List.copyOf(stepAcceptanceCriteria);
        }
    }

    record WorkspaceGitContext(
            String workspaceId,
            String sourceRepositoryId,
            WorkspaceMode mode,
            WorkspaceState state,
            long revision,
            String baseRef,
            String branchName,
            String persistedHeadCommit,
            String liveHeadCommit,
            String gitStatus,
            List<String> changedFiles,
            String patchSha256,
            String patch) {

        public WorkspaceGitContext {
            changedFiles = List.copyOf(changedFiles);
        }
    }

    record RuntimeContext(
            String agentRunId,
            String agentId,
            String runConfigurationSnapshotId,
            AgentRunState state,
            long revision,
            String executionPhase,
            String currentRunStepId,
            String currentRunStepType,
            RunStepState currentRunStepState,
            String checkpointId,
            int checkpointSequence,
            String checkpointSha256,
            long runEventCursor) {
    }

    record ConversationContext(
            String conversationId,
            String snapshotId,
            Integer snapshotVersion,
            String summary,
            Integer fromMessageSequence,
            Integer toMessageSequence,
            Integer tokenCount,
            String checksum) {
    }

    record ArtifactEvidence(
            String id,
            ArtifactType type,
            String contentHash,
            Instant createdAt) {
    }

    record TestEvidence(
            String artifactId,
            String executable,
            Integer exitCode,
            boolean passed,
            Instant createdAt) {
    }

    record AcceptanceEvidence(
            String artifactId,
            String criterion,
            boolean passed,
            Instant createdAt) {
    }

    record ModelCallEvidence(
            String id,
            String logicalCallId,
            ModelCallStatus status,
            String providerId,
            String modelId,
            String errorCode,
            Instant updatedAt) {
    }

    record UnknownToolEffect(
            String id,
            String toolCallId,
            String toolName,
            ToolExecutionStatus status,
            String resultRef,
            Instant updatedAt) {
    }

    record UnknownModelEffect(
            String id,
            String logicalCallId,
            ModelCallStatus status,
            String providerId,
            String modelId,
            String errorCode,
            Instant updatedAt) {
    }

    record ApprovalRequirement(
            String id,
            GovernanceActionType actionType,
            String resourceType,
            String resourceId,
            String summary,
            ApprovalState state,
            Instant expiresAt) {
    }
}
