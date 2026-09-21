package com.spaceagent.platform.automation.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.api.AgentCurrentConfigurationApplicationApi;
import com.spaceagent.platform.automation.api.AutomationApplicationApi;
import com.spaceagent.platform.automation.domain.AutomationExecution;
import com.spaceagent.platform.automation.domain.AutomationExecutionState;
import com.spaceagent.platform.automation.domain.AutomationRepository;
import com.spaceagent.platform.automation.domain.AutomationSchedule;
import com.spaceagent.platform.automation.domain.AutomationScheduleState;
import com.spaceagent.platform.automation.domain.AutomationTriggerType;
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.conversation.api.StartConversationCommand;
import com.spaceagent.platform.governance.api.GovernanceApplicationApi;
import com.spaceagent.platform.governance.domain.ApprovalState;
import com.spaceagent.platform.governance.domain.GovernanceActionType;
import com.spaceagent.platform.runtime.api.ChatRuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.PreparedChatExecutionCommand;
import com.spaceagent.platform.runtime.api.ResumeAgentRunCommand;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeCoordinationApplicationApi;
import com.spaceagent.platform.runtime.api.StartAgentRunCommand;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationType;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class AutomationExecutionCoordinator {

    private final AutomationRepository repository;
    private final GovernanceApplicationApi governance;
    private final ConversationApplicationApi conversations;
    private final RuntimeApplicationApi runtime;
    private final RuntimeCoordinationApplicationApi coordination;
    private final ChatRuntimeApplicationApi chat;
    private final IdGenerator ids;
    private final ObjectMapper json;
    private AgentCurrentConfigurationApplicationApi currentConfigurations;

    public AutomationExecutionCoordinator(
            AutomationRepository repository,
            GovernanceApplicationApi governance,
            ConversationApplicationApi conversations,
            RuntimeApplicationApi runtime,
            RuntimeCoordinationApplicationApi coordination,
            ChatRuntimeApplicationApi chat,
            IdGenerator ids,
            ObjectMapper json) {
        this.repository = repository;
        this.governance = governance;
        this.conversations = conversations;
        this.runtime = runtime;
        this.coordination = coordination;
        this.chat = chat;
        this.ids = ids;
        this.json = json;
    }

    @Autowired(required=false)
    public void setCurrentConfigurations(AgentCurrentConfigurationApplicationApi currentConfigurations) {
        this.currentConfigurations = currentConfigurations;
    }

    public AutomationExecution createOccurrence(
            AutomationSchedule schedule,
            String fireKey,
            Instant scheduledFor,
            AutomationTriggerType triggerType,
            String approvalId) {
        AutomationExecution existing = repository
                .findExecutionByFireKey(schedule.id(), fireKey).orElse(null);
        if (existing != null) {
            return existing;
        }
        String executionId = ids.nextId();
        var current = currentConfigurations == null ? null : currentConfigurations.requireCurrent(
                schedule.tenantId(), schedule.ownerId(), schedule.agentId());
        String operationHash = operationHash(
                schedule, executionId, fireKey, scheduledFor, triggerType,
                current == null ? "NON_SPRING_TEST_CONFIGURATION" : current.configHash());
        Instant now = repository.currentTime();
        AutomationExecution placeholder = new AutomationExecution(
                executionId, schedule.id(), schedule.tenantId(), schedule.ownerId(),
                schedule.agentId(), current == null ? null : current.configHash(),
                fireKey, scheduledFor, triggerType,
                AutomationExecutionState.PENDING_DISPATCH, operationHash, approvalId,
                null, null, null, null, null, null, null, 0, 0, 1, now, now);
        AutomationExecution claimed = repository.createOrFindExecution(placeholder);
        if (!claimed.id().equals(placeholder.id())) {
            return claimed;
        }
        var authorization = governance.authorize(new GovernanceApplicationApi.AuthorizeCommand(
                schedule.tenantId(), schedule.ownerId(), GovernanceActionType.AUTOMATION_TRIGGER,
                "AUTOMATION_SCHEDULE", schedule.id(), operationHash,
                "Trigger automation: " + schedule.description(), approvalId));
        String resolvedApprovalId = authorization.approval() == null
                ? approvalId : authorization.approval().id();
        AutomationExecution proposed;
        if (authorization.allowed()) {
            proposed = dispatched(claimed, schedule, true);
        } else {
            AutomationExecutionState authorizationState = state(authorization);
            proposed = copy(
                    claimed, authorizationState, resolvedApprovalId,
                    null, null, null, null, null,
                    terminal(authorizationState) ? now : null,
                    invalidReason(authorization), 0, 0, now);
        }
        AutomationExecution saved = transition(proposed, claimed.revision());
        if (terminal(saved.state())) {
            repository.recordOutcome(
                    saved.scheduleId(), now, saved.state(), saved.error());
        }
        return saved;
    }

    public boolean dispatchNextApproved() {
        AutomationExecution execution = repository.lockNextApprovalRequired().orElse(null);
        if (execution == null) {
            return false;
        }
        AutomationSchedule schedule = repository.findSchedule(execution.scheduleId())
                .orElseThrow(() -> new IllegalStateException(
                        "Automation schedule missing for execution " + execution.id()));
        if (schedule.state() == AutomationScheduleState.ARCHIVED
                || schedule.state() == AutomationScheduleState.FAILED) {
            Instant cancelledAt = repository.currentTime();
            String reason = "Automation schedule is "
                    + schedule.state().name().toLowerCase();
            transition(copy(
                    execution, AutomationExecutionState.CANCELLED,
                    execution.approvalId(), null, null, null, null,
                    null, cancelledAt, reason, 0, 0, cancelledAt), execution.revision());
            repository.recordOutcome(
                    execution.scheduleId(), cancelledAt,
                    AutomationExecutionState.CANCELLED, reason);
            return true;
        }
        var authorization = governance.authorize(new GovernanceApplicationApi.AuthorizeCommand(
                execution.tenantId(), execution.ownerId(), GovernanceActionType.AUTOMATION_TRIGGER,
                "AUTOMATION_SCHEDULE", execution.scheduleId(), execution.operationHash(),
                "Trigger automation: " + schedule.description(), execution.approvalId()));
        Instant now = repository.currentTime();
        if (authorization.allowed()) {
            AutomationExecution dispatched = dispatched(execution, schedule, true);
            transition(dispatched, execution.revision());
            return true;
        }
        if (authorization.status()
                == GovernanceApplicationApi.AuthorizationStatus.APPROVAL_REQUIRED) {
            transition(copy(
                    execution, AutomationExecutionState.APPROVAL_REQUIRED,
                    execution.approvalId(), null, null, null, null,
                    execution.startedAt(), null, null, 0, 0, now), execution.revision());
            return true;
        }
        AutomationExecutionState terminal = authorization.approval() != null
                && authorization.approval().state() == ApprovalState.EXPIRED
                ? AutomationExecutionState.EXPIRED : AutomationExecutionState.REJECTED;
        transition(copy(
                execution, terminal, execution.approvalId(), null, null, null, null,
                execution.startedAt(), now, "Automation approval " + terminal.name().toLowerCase(),
                0, 0, now), execution.revision());
        repository.recordOutcome(execution.scheduleId(), now, terminal,
                "Automation approval " + terminal.name().toLowerCase());
        return true;
    }

    public void executeDispatched(AutomationApplicationApi.ExecuteDispatchedCommand command) {
        AutomationExecution current = repository.findExecution(command.executionId())
                .orElseThrow(() -> new BusinessException(
                        "Automation execution not found", HttpStatus.NOT_FOUND,
                        "AUTOMATION_EXECUTION_NOT_FOUND"));
        if (!command.dispatchRunId().equals(current.dispatchRunId())) {
            throw new BusinessException(
                    "Automation dispatch Run mismatch", HttpStatus.CONFLICT,
                    "AUTOMATION_DISPATCH_RUN_MISMATCH");
        }
        if (current.state() == AutomationExecutionState.RUNNING) {
            markUnknown(current, "Previous Automation worker outcome is ambiguous");
            return;
        }
        if (terminal(current.state())) {
            return;
        }
        if (current.state() != AutomationExecutionState.QUEUED) {
            throw new BusinessException(
                    "Automation execution is not queued: " + current.state(),
                    HttpStatus.CONFLICT, "AUTOMATION_EXECUTION_STATE_CONFLICT");
        }
        runtime.resumeFenced(new ResumeAgentRunCommand(
                current.dispatchRunId(), command.leaseToken(), command.fencingToken()));
        Instant started = repository.currentTime();
        AutomationExecution running = transition(copy(
                current, AutomationExecutionState.RUNNING, current.approvalId(),
                current.conversationId(), current.dispatchRunId(), current.continuationId(),
                null, started, null, null, 0, 0, started), current.revision());
        AutomationSchedule schedule = repository.findSchedule(running.scheduleId()).orElseThrow();
        try {
            var result = chat.executePrepared(new PreparedChatExecutionCommand(
                    running.tenantId(), running.ownerId(), running.conversationId(),
                    running.agentId(), running.dispatchRunId(), running.dispatchRunId(),
                    schedule.prompt()));
            Instant completed = repository.currentTime();
            transition(copy(
                    running, AutomationExecutionState.SUCCEEDED, running.approvalId(),
                    running.conversationId(), running.dispatchRunId(), running.continuationId(),
                    result.agentRunId(), running.startedAt(), completed, null,
                    result.inputTokenCount(), result.outputTokenCount(), completed),
                    running.revision());
            repository.recordOutcome(
                    running.scheduleId(), completed, AutomationExecutionState.SUCCEEDED, null);
        } catch (RuntimeException error) {
            Instant completed = repository.currentTime();
            String reason = abbreviate(error.getMessage(), 2_000);
            transition(copy(
                    running, AutomationExecutionState.FAILED, running.approvalId(),
                    running.conversationId(), running.dispatchRunId(), running.continuationId(),
                    null, running.startedAt(), completed, reason, 0, 0, completed),
                    running.revision());
            repository.recordOutcome(
                    running.scheduleId(), completed, AutomationExecutionState.FAILED, reason);
            throw error;
        }
    }

    public int reconcileUnknown(int staleAfterSeconds, int limit) {
        Instant now = repository.currentTime();
        int reconciled = 0;
        for (AutomationExecution execution : repository.findRunningBefore(
                now.minusSeconds(staleAfterSeconds), limit)) {
            boolean claimActive = coordination.find(execution.continuationId())
                    .filter(value -> value.state()
                            == com.spaceagent.platform.runtime.domain.RuntimeContinuationState.CLAIMED)
                    .filter(value -> value.leaseUntil() != null && value.leaseUntil().isAfter(now))
                    .isPresent();
            if (claimActive) {
                continue;
            }
            markUnknown(execution, "Automation worker lease was lost after execution claim");
            reconciled++;
        }
        return reconciled;
    }

    private AutomationExecution dispatched(
            AutomationExecution execution, AutomationSchedule schedule, boolean incrementRevision) {
        var conversation = conversations.start(new StartConversationCommand(
                null, null, null, schedule.tenantId(), schedule.ownerId(), schedule.agentId(),
                abbreviate("Automation: " + schedule.description(), 120)));
        var run = runtime.startRun(new StartAgentRunCommand(
                schedule.tenantId(), schedule.ownerId(), schedule.agentId(),
                null, conversation.id(), null, null, null, null));
        String payload;
        try {
            payload = json.writeValueAsString(Map.of("executionId", execution.id()));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to encode Automation continuation", error);
        }
        var continuation = coordination.enqueue(
                new RuntimeCoordinationApplicationApi.EnqueueContinuationCommand(
                        run.id(), RuntimeContinuationType.AUTOMATION_EXECUTION,
                        "automation:" + execution.id(), payload, null, 1));
        return new AutomationExecution(
                execution.id(), execution.scheduleId(), execution.tenantId(), execution.ownerId(),
                execution.agentId(), null, execution.fireKey(),
                execution.scheduledFor(), execution.triggerType(), AutomationExecutionState.QUEUED,
                execution.operationHash(), execution.approvalId(), conversation.id(), run.id(),
                continuation.id(), null, null, null, null, 0, 0,
                incrementRevision ? execution.revision() + 1 : execution.revision(),
                execution.createdAt(), repository.currentTime());
    }

    private void markUnknown(AutomationExecution execution, String reason) {
        Instant now = repository.currentTime();
        transition(copy(
                execution, AutomationExecutionState.UNKNOWN, execution.approvalId(),
                execution.conversationId(), execution.dispatchRunId(), execution.continuationId(),
                execution.agentRunId(), execution.startedAt(), now, reason,
                execution.inputTokens(), execution.outputTokens(), now), execution.revision());
        repository.recordOutcome(
                execution.scheduleId(), now, AutomationExecutionState.UNKNOWN, reason);
    }

    private AutomationExecution transition(AutomationExecution value, long expectedRevision) {
        return repository.transitionExecution(value, expectedRevision)
                .orElseThrow(() -> new BusinessException(
                        "Automation execution revision conflict", HttpStatus.CONFLICT,
                        "AUTOMATION_EXECUTION_REVISION_CONFLICT"));
    }

    private String operationHash(
            AutomationSchedule schedule,
            String executionId,
            String fireKey,
            Instant scheduledFor,
            AutomationTriggerType triggerType,
            String configurationHash) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("scheduleId", schedule.id());
        canonical.put("scheduleRevision", schedule.revision());
        canonical.put("executionId", executionId);
        canonical.put("fireKey", fireKey);
        canonical.put("scheduledFor", scheduledFor.toString());
        canonical.put("triggerType", triggerType.name());
        canonical.put("agentConfigurationHash", configurationHash);
        canonical.put("promptHash", sha256(schedule.prompt()));
        try {
            return "sha256:" + sha256(json.writeValueAsString(canonical));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash Automation operation", error);
        }
    }

    private static AutomationExecutionState state(
            GovernanceApplicationApi.AuthorizationView authorization) {
        if (authorization.allowed()) {
            return AutomationExecutionState.QUEUED;
        }
        if (authorization.status()
                == GovernanceApplicationApi.AuthorizationStatus.APPROVAL_REQUIRED) {
            return AutomationExecutionState.APPROVAL_REQUIRED;
        }
        return authorization.approval() != null
                && authorization.approval().state() == ApprovalState.EXPIRED
                ? AutomationExecutionState.EXPIRED : AutomationExecutionState.REJECTED;
    }

    private static String invalidReason(
            GovernanceApplicationApi.AuthorizationView authorization) {
        return authorization.status()
                == GovernanceApplicationApi.AuthorizationStatus.INVALID_APPROVAL
                ? "Automation approval is invalid" : null;
    }

    private static boolean terminal(AutomationExecutionState state) {
        return state == AutomationExecutionState.SUCCEEDED
                || state == AutomationExecutionState.FAILED
                || state == AutomationExecutionState.UNKNOWN
                || state == AutomationExecutionState.REJECTED
                || state == AutomationExecutionState.EXPIRED
                || state == AutomationExecutionState.CANCELLED;
    }

    private static AutomationExecution copy(
            AutomationExecution value,
            AutomationExecutionState state,
            String approvalId,
            String conversationId,
            String dispatchRunId,
            String continuationId,
            String agentRunId,
            Instant startedAt,
            Instant completedAt,
            String error,
            int inputTokens,
            int outputTokens,
            Instant updatedAt) {
        return new AutomationExecution(
                value.id(), value.scheduleId(), value.tenantId(), value.ownerId(), value.agentId(),
                value.configurationHash(), value.fireKey(), value.scheduledFor(), value.triggerType(),
                state, value.operationHash(), approvalId, conversationId, dispatchRunId,
                continuationId, agentRunId, startedAt, completedAt, error, inputTokens,
                outputTokens, value.revision() + 1, value.createdAt(), updatedAt);
    }

    private static String abbreviate(String value, int limit) {
        String normalized = value == null || value.isBlank() ? "Automation failed" : value.trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
