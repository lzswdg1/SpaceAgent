package com.spaceagent.platform.runtime.api;

import com.spaceagent.platform.artifact.api.ArtifactApplicationApi;
import com.spaceagent.platform.project.domain.WorkspaceCodingGateway;

import java.util.List;

public interface CodingRuntimeApplicationApi {
    CodingRunView start(StartCommand command);
    CodingActionView execute(ActionCommand command);
    CodingActionView evidence(EvidenceCommand command);
    CodingRunView prepareCompletion(FinalizeCommand command);
    CodingRunView finalizeRun(FinalizeCommand command);
    List<ArtifactApplicationApi.ArtifactView> artifacts(String userId, String runId);

    record StartCommand(
            String tenantId,
            String userId,
            String agentId,
            String configurationSnapshotId,
            String conversationId,
            String projectId,
            String taskId,
            String taskPlanId,
            String planStepId,
            String workspaceId) {}

    record ActionCommand(
            String userId,
            String agentRunId,
            String workspaceId,
            String toolCallId,
            WorkspaceCodingGateway.Type type,
            String relativePath,
            String content,
            String executable,
            List<String> arguments,
            int timeoutSeconds,
            String approvalId) {

        public ActionCommand {
            arguments = arguments == null ? List.of() : List.copyOf(arguments);
        }

        /** Source-compatible constructor for clients that do not opt into approval gates. */
        public ActionCommand(
                String userId,
                String agentRunId,
                String workspaceId,
                String toolCallId,
                WorkspaceCodingGateway.Type type,
                String relativePath,
                String content,
                String executable,
                List<String> arguments,
                int timeoutSeconds) {
            this(userId, agentRunId, workspaceId, toolCallId, type, relativePath, content,
                    executable, arguments, timeoutSeconds, null);
        }
    }

    record EvidenceCommand(
            String userId,
            String agentRunId,
            String workspaceId,
            String criterion,
            boolean passed,
            String details) {}

    record FinalizeCommand(
            String userId, String agentRunId, String workspaceId, String commitMessage) {}

    record CodingRunView(
            String agentRunId, String workspaceId, String state, List<String> artifactIds) {}

    record CodingActionView(
            String agentRunId,
            String toolCallId,
            String status,
            int exitCode,
            String output,
            List<String> changedFiles,
            String artifactId) {}
}
