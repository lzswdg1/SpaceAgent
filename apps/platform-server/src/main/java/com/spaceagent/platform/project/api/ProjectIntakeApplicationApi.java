package com.spaceagent.platform.project.api;

import com.spaceagent.platform.project.domain.ProjectIntakeState;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ProjectIntakeApplicationApi {

    JobView enqueue(EnqueueCommand command);

    JobView get(Query query);

    JobPageView list(ListQuery query);

    Optional<ClaimView> claim(String workerId, int leaseSeconds, int maximumAttempts);

    JobView provisionWorkspace(ClaimCommand command);

    JobView attachRun(AttachRunCommand command);

    JobView recordInspection(InspectionCommand command);

    String readInspection(ClaimCommand command);

    JobView publishProposal(ProposalCommand command);

    JobView fail(FailCommand command);

    JobView confirm(ConfirmCommand command);

    JobView reject(RejectCommand command);

    boolean cleanupOneWorkspace();

    record EnqueueCommand(
            String tenantId,
            String userId,
            String projectId,
            String projectDirectoryId,
            String conversationId,
            String agentId,
            String goal,
            String idempotencyKey) {
    }

    record Query(
            String tenantId, String userId, String projectId,
            String projectDirectoryId, String jobId) {
    }

    record ListQuery(
            String tenantId, String userId, String projectId,
            String projectDirectoryId, int page, int pageSize) {
    }

    record ClaimCommand(String jobId, String workerId, String claimToken, long fencingToken) {
    }

    record AttachRunCommand(
            String jobId, String workerId, String claimToken, long fencingToken,
            String agentRunId) {
    }

    record InspectionCommand(
            String jobId, String workerId, String claimToken, long fencingToken,
            String sourceHeadCommit, String inspectionHash, String inspectionJson) {
    }

    record ProposalCommand(
            String jobId, String workerId, String claimToken, long fencingToken,
            String proposalHash, String proposalJson) {
    }

    record FailCommand(
            String jobId, String workerId, String claimToken, long fencingToken,
            String safeErrorCode, boolean blocked) {
    }

    record ConfirmCommand(
            String tenantId, String userId, String projectId,
            String projectDirectoryId, String jobId, String expectedProposalHash) {
    }

    record RejectCommand(
            String tenantId, String userId, String projectId,
            String projectDirectoryId, String jobId, String expectedProposalHash,
            String reason) {
    }

    record ClaimView(
            JobView job, String claimToken, long fencingToken, Instant leaseUntil) {
    }

    record JobPageView(List<JobView> items, int page, int pageSize, long total) {
        public JobPageView {
            items = List.copyOf(items);
        }
    }

    record JobView(
            String id,
            String tenantId,
            String ownerId,
            String projectId,
            String projectDirectoryId,
            String sourceRepositoryId,
            String conversationId,
            String agentId,
            String goal,
            ProjectIntakeState state,
            int attempt,
            String sourceHeadCommit,
            InspectionView inspection,
            String proposalHash,
            ProjectIntakeProposal proposal,
            String agentRunId,
            String blueprintId,
            String rootTaskId,
            String taskPlanId,
            String safeErrorCode,
            String reviewedBy,
            String reviewReason,
            Instant reviewedAt,
            long revision,
            Instant createdAt,
            Instant startedAt,
            Instant updatedAt,
            Instant completedAt,
            Instant workspaceCleanedAt) {
    }

    record InspectionView(
            String sha256,
            String headCommit,
            int trackedFileCount,
            List<String> trackedPaths,
            List<InspectedFileView> selectedFiles) {
        public InspectionView {
            trackedPaths = trackedPaths == null ? List.of() : List.copyOf(trackedPaths);
            selectedFiles = selectedFiles == null ? List.of() : List.copyOf(selectedFiles);
        }
    }

    record InspectedFileView(String path, String sha256, boolean truncated) {
    }

    record ProjectIntakeProposal(
            BlueprintDraft blueprint,
            TaskDraft rootTask,
            List<ChildTaskDraft> childTasks,
            List<PlanStepDraft> steps) {
        public ProjectIntakeProposal {
            childTasks = childTasks == null ? List.of() : List.copyOf(childTasks);
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }

    record BlueprintDraft(
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
            List<String> acceptanceCriteria) {
        public BlueprintDraft {
            requirements = copy(requirements);
            modules = copy(modules);
            boundaries = copy(boundaries);
            architectureDecisions = copy(architectureDecisions);
            commands = commands == null ? Map.of() : Map.copyOf(commands);
            environmentRefs = copy(environmentRefs);
            conventions = copy(conventions);
            forbiddenAreas = copy(forbiddenAreas);
            risks = copy(risks);
            openQuestions = copy(openQuestions);
            acceptanceCriteria = copy(acceptanceCriteria);
        }
    }

    record TaskDraft(
            String title,
            String goal,
            String description,
            List<String> constraints,
            List<String> acceptanceCriteria) {
        public TaskDraft {
            constraints = copy(constraints);
            acceptanceCriteria = copy(acceptanceCriteria);
        }
    }

    record ChildTaskDraft(
            String key,
            String title,
            String goal,
            String description,
            List<String> constraints,
            List<String> acceptanceCriteria) {
        public ChildTaskDraft {
            constraints = copy(constraints);
            acceptanceCriteria = copy(acceptanceCriteria);
        }
    }

    record PlanStepDraft(
            String stepKey,
            String childTaskKey,
            List<String> dependsOnStepKeys,
            String requiredCapability,
            String expectedOutput,
            List<String> acceptanceCriteria,
            boolean approvalRequired) {
        public PlanStepDraft {
            dependsOnStepKeys = copy(dependsOnStepKeys);
            acceptanceCriteria = copy(acceptanceCriteria);
        }
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
