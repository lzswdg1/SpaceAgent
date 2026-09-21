package com.spaceagent.platform.integration.application;

import com.spaceagent.platform.automation.api.AutomationInternalEventApplicationApi;
import com.spaceagent.platform.automation.domain.AutomationTriggerSource;
import com.spaceagent.platform.automation.domain.AutomationTriggerType;
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.conversation.domain.ConversationStatus;
import com.spaceagent.platform.project.api.GetChatTaskQuery;
import com.spaceagent.platform.project.api.GetTaskQuery;
import com.spaceagent.platform.project.api.TaskApplicationApi;
import com.spaceagent.platform.project.api.TaskView;
import com.spaceagent.platform.project.domain.TaskState;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class AutomationInternalEventCoordinator {
    private final AutomationInternalEventApplicationApi automation;
    private final TaskApplicationApi tasks;
    private final ConversationApplicationApi conversations;
    private final TimeProvider time;

    public AutomationInternalEventCoordinator(
            AutomationInternalEventApplicationApi automation,
            TaskApplicationApi tasks,
            ConversationApplicationApi conversations,
            TimeProvider time) {
        this.automation = automation;
        this.tasks = tasks;
        this.conversations = conversations;
        this.time = time;
    }

    public Result taskCompleted(String triggerVersionId, String subscriptionId) {
        var policy = automation.prepare(triggerVersionId, subscriptionId);
        if (policy.type() != AutomationTriggerType.TASK_COMPLETION) throw invalid();
        TaskView task;
        try {
            task = tasks.getTask(new GetTaskQuery(
                    policy.tenantId(), policy.ownerId(), policy.projectId(), policy.taskId()));
        } catch (RuntimeException error) {
            throw invalid();
        }
        AutomationTriggerSource.TaskOutcome outcome = outcome(task.state());
        if (!policy.projectId().equals(task.projectId()) || !policy.taskId().equals(task.id())
                || outcome == null || !policy.outcomes().contains(outcome)) throw invalid();
        String canonical = String.join("\n", "TASK_COMPLETION", task.projectId(), task.id(),
                outcome.name(), task.updatedAt().toString());
        return admit(policy, canonical, task.updatedAt());
    }

    public Result followUpDue(String triggerVersionId, String subscriptionId) {
        var policy = automation.prepare(triggerVersionId, subscriptionId);
        if (policy.type() != AutomationTriggerType.FOLLOW_UP) throw invalid();
        TaskView task;
        try {
            var conversation = conversations.find(policy.conversationId()).orElseThrow();
            if (!conversation.tenantId().equals(policy.tenantId())
                    || !conversation.userId().equals(policy.ownerId())
                    || conversation.status() != ConversationStatus.ACTIVE) throw invalid();
            task = tasks.getChatTask(new GetChatTaskQuery(
                    policy.tenantId(), policy.ownerId(), policy.conversationId(), policy.sourceTaskId()));
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw invalid();
        }
        if (!policy.sourceTaskId().equals(task.id())
                || !policy.conversationId().equals(task.conversationId())
                || !task.state().isTerminal()) throw invalid();
        Instant dueAt = task.updatedAt().plusSeconds(policy.delaySeconds());
        if (time.now().isBefore(dueAt)) {
            throw new BusinessException("Follow-up is not due", HttpStatus.CONFLICT,
                    "AUTOMATION_FOLLOW_UP_NOT_DUE");
        }
        String canonical = String.join("\n", "FOLLOW_UP", policy.conversationId(), task.id(),
                task.state().name(), task.updatedAt().toString(), policy.delaySeconds().toString());
        return admit(policy, canonical, dueAt);
    }

    private Result admit(
            AutomationInternalEventApplicationApi.InternalPolicy policy,
            String canonical,
            Instant occurredAt) {
        String digest = sha256(canonical);
        var result = automation.admit(new AutomationInternalEventApplicationApi.VerifiedInternalEvent(
                policy, digest, digest, occurredAt));
        return new Result(result.occurrenceId());
    }

    private static AutomationTriggerSource.TaskOutcome outcome(TaskState state) {
        return switch (state) {
            case COMPLETED -> AutomationTriggerSource.TaskOutcome.SUCCEEDED;
            case FAILED -> AutomationTriggerSource.TaskOutcome.FAILED;
            case CANCELLED -> AutomationTriggerSource.TaskOutcome.CANCELLED;
            default -> null;
        };
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static BusinessException invalid() {
        return new BusinessException("Internal Automation event evidence is invalid",
                HttpStatus.CONFLICT, "AUTOMATION_INTERNAL_EVENT_INVALID");
    }

    public record Result(String occurrenceId) {
    }
}
