package com.spaceagent.platform.integration.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.api.AgentApplicationApi;
import com.spaceagent.platform.agent.api.AgentCurrentConfigurationApplicationApi;
import com.spaceagent.platform.agent.domain.AgentDefinitionStatus;
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.conversation.domain.ConversationStatus;
import com.spaceagent.platform.memory.api.MemoryApplicationApi;
import com.spaceagent.platform.memory.api.SaveProjectMemorySnapshotCommand;
import com.spaceagent.platform.memory.domain.MemoryKind;
import com.spaceagent.platform.project.api.TaskExecutionApplicationApi;
import com.spaceagent.platform.project.api.WorkspaceApplicationApi;
import com.spaceagent.platform.project.domain.PlanStepState;
import com.spaceagent.platform.project.domain.WorkspaceMode;
import com.spaceagent.platform.project.domain.WorkspaceState;
import com.spaceagent.platform.runtime.api.AgentRunView;
import com.spaceagent.platform.runtime.api.ProjectCodingJobApplicationApi;
import com.spaceagent.platform.runtime.api.ProjectRecoveryApplicationApi;
import com.spaceagent.platform.runtime.api.ProjectRunHandoffApplicationApi;
import com.spaceagent.platform.runtime.api.ProjectPlanStepAssignmentApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.ProjectCodingJobState;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Coordinates owner APIs; Runtime, Project and Memory retain their own state. */
@Service
public class ProjectRunHandoffCoordinator {
    private final ProjectCodingJobApplicationApi jobs;
    private final ProjectRunHandoffApplicationApi handoffs;
    private final ProjectRecoveryApplicationApi recovery;
    private final RuntimeApplicationApi runtime;
    private final TaskExecutionApplicationApi taskExecution;
    private final WorkspaceApplicationApi workspaces;
    private final ConversationApplicationApi conversations;
    private final AgentApplicationApi agents;
    private final AgentCurrentConfigurationApplicationApi currentConfigurations;
    private final MemoryApplicationApi memory;
    private final IdGenerator ids;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;
    private ProjectPlanStepAssignmentApplicationApi assignments;

    public ProjectRunHandoffCoordinator(
            ProjectCodingJobApplicationApi jobs,
            ProjectRunHandoffApplicationApi handoffs,
            ProjectRecoveryApplicationApi recovery,
            RuntimeApplicationApi runtime,
            TaskExecutionApplicationApi taskExecution,
            WorkspaceApplicationApi workspaces,
            ConversationApplicationApi conversations,
            AgentApplicationApi agents,
            AgentCurrentConfigurationApplicationApi currentConfigurations,
            MemoryApplicationApi memory,
            IdGenerator ids,
            ObjectMapper json,
            PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.handoffs = handoffs;
        this.recovery = recovery;
        this.runtime = runtime;
        this.taskExecution = taskExecution;
        this.workspaces = workspaces;
        this.conversations = conversations;
        this.agents = agents;
        this.currentConfigurations = currentConfigurations;
        this.memory = memory;
        this.ids = ids;
        this.json = json;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Autowired(required = false)
    void setAssignments(ProjectPlanStepAssignmentApplicationApi assignments) {
        this.assignments = assignments;
    }

    public Result create(CreateCommand command) {
        String key = required(command.idempotencyKey(), "Idempotency-Key", 200);
        if (key.length() < 8) throw invalid("Idempotency-Key is too short");
        ProjectCodingJobApplicationApi.JobView source = jobs.get(
                new ProjectCodingJobApplicationApi.Query(
                        command.tenantId(), command.ownerId(), command.projectId(),
                        command.taskPlanId(), command.planStepId(), command.sourceCodingJobId()));
        ProjectRunHandoffApplicationApi.HandoffView prior = handoffs
                .findBySourceCodingJobId(source.id()).orElse(null);
        if (prior != null) return replay(command, key);
        if (source.state() != ProjectCodingJobState.WAITING_APPROVAL
                && source.state() != ProjectCodingJobState.BLOCKED) {
            throw conflict("PROJECT_HANDOFF_SOURCE_STATE_INVALID");
        }
        if (source.state() == ProjectCodingJobState.BLOCKED
                && source.safeErrorCode() != null && source.safeErrorCode().contains("UNKNOWN")
                && handoffs.findByTargetCodingJobId(source.id()).isPresent()) {
            throw conflict("PROJECT_HANDOFF_UNKNOWN_RECONCILIATION_REQUIRED");
        }
        if (source.codingRunId() == null || source.workspaceId() == null) {
            throw conflict("PROJECT_HANDOFF_SOURCE_INCOMPLETE");
        }
        AgentRunView sourceRun = runtime.findRun(source.codingRunId())
                .filter(value -> exactSource(value, source))
                .orElseThrow(() -> conflict("PROJECT_HANDOFF_SOURCE_SCOPE_MISMATCH"));
        if (isTerminal(sourceRun.state())) {
            throw conflict("PROJECT_HANDOFF_SOURCE_TERMINAL");
        }
        var reference = taskExecution.resolve(
                new com.spaceagent.platform.project.api.ResolveTaskExecutionReferenceQuery(
                        source.tenantId(), source.ownerId(), source.projectId(), source.taskId(),
                        source.taskPlanId(), source.planStepId()));
        if (reference.planStepState() != PlanStepState.IN_PROGRESS) {
            throw conflict("PROJECT_HANDOFF_PLAN_STEP_NOT_ACTIVE");
        }
        var workspace = workspaces.get(new WorkspaceApplicationApi.Query(
                source.tenantId(), source.ownerId(), source.projectId(), source.workspaceId()));
        if (!source.projectDirectoryId().equals(workspace.projectDirectoryId())
                || !source.taskId().equals(workspace.taskId())
                || workspace.mode() != WorkspaceMode.MANAGED_GIT
                || workspace.state() != WorkspaceState.READY) {
            throw conflict("PROJECT_HANDOFF_WORKSPACE_INVALID");
        }
        validateConversation(command, source);
        var targetConfiguration = validateAgent(
                command.tenantId(), command.ownerId(), command.targetAgentId());
        validateAgent(command.tenantId(), command.ownerId(), command.reviewerAgentId());
        if (command.reviewerAgentId().equals(command.targetAgentId())) {
            throw conflict("PROJECT_HANDOFF_REVIEWER_NOT_DISTINCT");
        }
        materializeHandoffAssignment(command, targetConfiguration.modelPoolId());
        ProjectRecoveryApplicationApi.CodingRecoveryPackageView snapshot = recovery.capture(
                new ProjectRecoveryApplicationApi.CaptureCommand(
                        source.tenantId(), source.ownerId(), source.projectId(), source.codingRunId(),
                        "project-handoff-recovery:" + hash(key)));
        String handoffId = ids.nextId();
        return transactions.execute(status -> {
            jobs.handoff(new ProjectCodingJobApplicationApi.HandoffCommand(
                    source.tenantId(), source.ownerId(), source.projectId(), source.taskPlanId(),
                    source.planStepId(), source.id()));
            runtime.markRunHandedOff(source.codingRunId(), handoffId);
            ProjectCodingJobApplicationApi.JobView target = jobs.enqueue(
                    new ProjectCodingJobApplicationApi.EnqueueCommand(
                            source.tenantId(), source.ownerId(), source.projectId(),
                            source.projectDirectoryId(), command.targetConversationId(),
                            source.sourceRepositoryId(), source.rootTaskId(), source.taskId(),
                            source.taskPlanId(), source.executionId(), source.planStepId(), command.targetAgentId(),
                            null, null, source.baseRef(), "project-handoff-target:" + hash(key),
                            command.reviewerAgentId()));
            ProjectRunHandoffApplicationApi.HandoffView handoff = handoffs.create(
                    new ProjectRunHandoffApplicationApi.CreateCommand(
                            handoffId, source.tenantId(), source.ownerId(), source.projectId(),
                            source.projectDirectoryId(), source.sourceRepositoryId(),
                            source.rootTaskId(), source.taskId(), source.taskPlanId(),
                            source.planStepId(), source.baseRef(), source.id(), source.codingRunId(),
                            source.workspaceId(), snapshot.snapshotId(), snapshot.snapshotSha256(),
                            command.targetConversationId(), command.targetAgentId(),
                            command.reviewerAgentId(), target.id(), key));
            memory.saveProjectSnapshot(new SaveProjectMemorySnapshotCommand(
                    source.projectId(), MemoryKind.DECISION, "handoff:" + handoff.id(),
                    initialMemory(handoff, snapshot)));
            return new Result(handoff, target, snapshot);
        });
    }

    private void materializeHandoffAssignment(CreateCommand command, String modelPoolId) {
        if (assignments == null) return;
        if (modelPoolId == null) throw conflict("ASSIGNMENT_REQUIRED");
        assignments.handoff(new ProjectPlanStepAssignmentApplicationApi.HandoffAssignmentCommand(
                command.tenantId(), command.ownerId(), command.projectId(), command.taskPlanId(), command.planStepId(),
                command.targetAgentId(), null, command.reviewerAgentId(),
                null, modelPoolId, null, null));
    }

    public ProjectRunHandoffApplicationApi.HandoffView get(
            String tenantId, String ownerId, String projectId, String handoffId) {
        return handoffs.get(new ProjectRunHandoffApplicationApi.Query(
                tenantId, ownerId, projectId, handoffId));
    }

    public ProjectRunHandoffApplicationApi.HandoffPage list(
            String tenantId, String ownerId, String projectId, int page, int pageSize) {
        return handoffs.list(new ProjectRunHandoffApplicationApi.ListQuery(
                tenantId, ownerId, projectId, page, pageSize));
    }

    private Result replay(CreateCommand command, String key) {
        var prior = handoffs.replayBySource(new ProjectRunHandoffApplicationApi.ReplayCommand(
                command.sourceCodingJobId(), key, command.targetConversationId(),
                command.targetAgentId(), command.reviewerAgentId()));
        ProjectCodingJobApplicationApi.JobView target = jobs.get(
                new ProjectCodingJobApplicationApi.Query(
                        prior.tenantId(), prior.ownerId(), prior.projectId(), prior.taskPlanId(),
                        prior.planStepId(), prior.targetCodingJobId()));
        ProjectRecoveryApplicationApi.CodingRecoveryPackageView snapshot = recovery.get(
                new ProjectRecoveryApplicationApi.Query(
                        prior.tenantId(), prior.ownerId(), prior.projectId(),
                        prior.sourceAgentRunId(), prior.recoverySnapshotId()));
        return new Result(prior, target, snapshot);
    }

    private void validateConversation(
            CreateCommand command, ProjectCodingJobApplicationApi.JobView source) {
        conversations.find(command.targetConversationId())
                .filter(value -> value.tenantId().equals(command.tenantId()))
                .filter(value -> value.userId().equals(command.ownerId()))
                .filter(value -> source.projectId().equals(value.projectId()))
                .filter(value -> source.projectDirectoryId().equals(value.projectDirectoryId()))
                .filter(value -> value.status() == ConversationStatus.ACTIVE)
                .orElseThrow(() -> conflict("PROJECT_HANDOFF_TARGET_CONVERSATION_INVALID"));
    }

    private AgentCurrentConfigurationApplicationApi.ConfigurationView validateAgent(
            String tenantId, String ownerId, String agentId) {
        agents.findById(agentId)
                .filter(value -> value.tenantId().equals(tenantId))
                .filter(value -> value.ownerId().equals(ownerId))
                .filter(value -> value.status() == AgentDefinitionStatus.ACTIVE)
                .orElseThrow(() -> conflict("PROJECT_HANDOFF_TARGET_AGENT_INVALID"));
        return currentConfigurations.requireCurrent(tenantId, ownerId, agentId);
    }

    private String initialMemory(
            ProjectRunHandoffApplicationApi.HandoffView handoff,
            ProjectRecoveryApplicationApi.CodingRecoveryPackageView snapshot) {
        try {
            var context = snapshot.context();
            String sourceConversation = context == null || context.conversation() == null
                    ? "unavailable" : context.conversation().conversationId();
            String nextAction = context == null || context.nextAction() == null
                    ? "REVALIDATE_RECOVERY_PACKAGE" : context.nextAction();
            List<String> blockers = context == null ? List.of() : context.blockers();
            return json.writeValueAsString(Map.of(
                    "handoffId", handoff.id(),
                    "recoverySnapshotId", snapshot.snapshotId(),
                    "recoverySnapshotHash", snapshot.snapshotSha256(),
                    "sourceConversationId", sourceConversation,
                    "targetConversationId", handoff.targetConversationId(),
                    "sourceAgentRunId", handoff.sourceAgentRunId(),
                    "targetAgentId", handoff.targetAgentId(),
                    "planStepId", handoff.planStepId(),
                    "nextAction", nextAction,
                    "blockers", blockers));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize Project handoff memory", error);
        }
    }

    private static boolean exactSource(
            AgentRunView run, ProjectCodingJobApplicationApi.JobView source) {
        return source.tenantId().equals(run.tenantId())
                && source.ownerId().equals(run.ownerId())
                && source.projectId().equals(run.projectId())
                && source.projectDirectoryId().equals(run.projectDirectoryId())
                && source.workspaceId().equals(run.workspaceId())
                && source.taskId().equals(run.taskId())
                && source.taskPlanId().equals(run.taskPlanId())
                && source.planStepId().equals(run.planStepId());
    }

    private static boolean isTerminal(AgentRunState state) {
        return state == AgentRunState.COMPLETED || state == AgentRunState.FAILED
                || state == AgentRunState.CANCELLED;
    }

    private static String required(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.trim().length() > maximum) {
            throw invalid(field + " is invalid");
        }
        return value.trim();
    }

    private static String hash(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(message, HttpStatus.BAD_REQUEST, "PROJECT_HANDOFF_INVALID");
    }

    private static BusinessException conflict(String code) {
        return new BusinessException("Project handoff conflict", HttpStatus.CONFLICT, code);
    }

    public record CreateCommand(
            String tenantId, String ownerId, String projectId, String taskPlanId,
            String planStepId, String sourceCodingJobId, String targetConversationId,
            String targetAgentId, String reviewerAgentId, String idempotencyKey) {}

    public record Result(
            ProjectRunHandoffApplicationApi.HandoffView handoff,
            ProjectCodingJobApplicationApi.JobView targetCodingJob,
            ProjectRecoveryApplicationApi.CodingRecoveryPackageView recoveryPackage) {}
}
