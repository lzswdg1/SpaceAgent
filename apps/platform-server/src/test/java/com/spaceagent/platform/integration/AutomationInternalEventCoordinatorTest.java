package com.spaceagent.platform.integration;

import com.spaceagent.platform.automation.application.AutomationInternalEventApplicationService;
import com.spaceagent.platform.automation.domain.AutomationTrigger;
import com.spaceagent.platform.automation.domain.AutomationTriggerPersistence;
import com.spaceagent.platform.automation.domain.AutomationTriggerSource;
import com.spaceagent.platform.automation.domain.AutomationTriggerState;
import com.spaceagent.platform.automation.domain.AutomationTriggerType;
import com.spaceagent.platform.automation.infrastructure.memory.InMemoryAutomationTriggerRepository;
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.conversation.api.ConversationView;
import com.spaceagent.platform.conversation.domain.ConversationStatus;
import com.spaceagent.platform.integration.application.AutomationInternalEventCoordinator;
import com.spaceagent.platform.project.api.TaskApplicationApi;
import com.spaceagent.platform.project.api.TaskView;
import com.spaceagent.platform.project.domain.TaskState;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutomationInternalEventCoordinatorTest {
    private static final Instant UPDATED = Instant.parse("2026-09-09T00:00:00Z");
    private InMemoryAutomationTriggerRepository repository;
    private TaskApplicationApi tasks;
    private ConversationApplicationApi conversations;
    private AtomicReference<Instant> now;
    private String taskTrigger;
    private String taskSubscription;
    private String followTrigger;
    private String followSubscription;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAutomationTriggerRepository();
        tasks = mock(TaskApplicationApi.class);
        conversations = mock(ConversationApplicationApi.class);
        now = new AtomicReference<>(UPDATED.plusSeconds(300));
        taskTrigger = addTrigger(new AutomationTriggerSource.TaskCompletion(
                "project", "project-task", Set.of(AutomationTriggerSource.TaskOutcome.SUCCEEDED)));
        taskSubscription = subscription(taskTrigger, AutomationTriggerType.TASK_COMPLETION);
        followTrigger = addTrigger(new AutomationTriggerSource.FollowUp("conversation", "chat-task", 300));
        followSubscription = subscription(followTrigger, AutomationTriggerType.FOLLOW_UP);
    }

    @Test
    void projectTerminalCompletionCreatesAndReplaysStableOccurrence() {
        when(tasks.getTask(any())).thenReturn(projectTask(TaskState.COMPLETED));
        var coordinator = coordinator();
        var first = coordinator.taskCompleted(taskTrigger, taskSubscription);
        var replay = coordinator.taskCompleted(taskTrigger, taskSubscription);
        assertThat(replay.occurrenceId()).isEqualTo(first.occurrenceId());
        assertThat(repository.findOccurrence("tenant", "owner", first.occurrenceId())).isPresent();
    }

    @Test
    void nonTerminalDisallowedOrMismatchedProjectEvidenceFailsClosed() {
        when(tasks.getTask(any())).thenReturn(projectTask(TaskState.IN_PROGRESS));
        assertCode(() -> coordinator().taskCompleted(taskTrigger, taskSubscription),
                "AUTOMATION_INTERNAL_EVENT_INVALID");
        when(tasks.getTask(any())).thenReturn(new TaskView(
                "project-task", "other-project", null, "Task", "Goal", null, List.of(), List.of(),
                null, TaskState.COMPLETED, UPDATED, UPDATED));
        assertCode(() -> coordinator().taskCompleted(taskTrigger, taskSubscription),
                "AUTOMATION_INTERNAL_EVENT_INVALID");
    }

    @Test
    void followUpRequiresExactActiveConversationTerminalTaskAndDueTime() {
        when(conversations.find("conversation")).thenReturn(java.util.Optional.of(conversation("tenant", "owner")));
        when(tasks.getChatTask(any())).thenReturn(chatTask(TaskState.COMPLETED));
        now.set(UPDATED.plusSeconds(299));
        assertCode(() -> coordinator().followUpDue(followTrigger, followSubscription),
                "AUTOMATION_FOLLOW_UP_NOT_DUE");
        now.set(UPDATED.plusSeconds(300));
        var first = coordinator().followUpDue(followTrigger, followSubscription);
        var replay = coordinator().followUpDue(followTrigger, followSubscription);
        assertThat(replay.occurrenceId()).isEqualTo(first.occurrenceId());
    }

    @Test
    void crossTenantOrClosedConversationCannotCreateFollowUp() {
        when(tasks.getChatTask(any())).thenReturn(chatTask(TaskState.COMPLETED));
        when(conversations.find("conversation")).thenReturn(
                java.util.Optional.of(conversation("other", "owner")));
        assertCode(() -> coordinator().followUpDue(followTrigger, followSubscription),
                "AUTOMATION_INTERNAL_EVENT_INVALID");
        when(conversations.find("conversation")).thenReturn(java.util.Optional.of(new ConversationView(
                "conversation", null, null, "chat-task", "chat-task", "tenant", "owner", "agent",
                "Closed", ConversationStatus.CLOSED, UPDATED, UPDATED)));
        assertCode(() -> coordinator().followUpDue(followTrigger, followSubscription),
                "AUTOMATION_INTERNAL_EVENT_INVALID");
    }

    private AutomationInternalEventCoordinator coordinator() {
        AtomicInteger sequence = new AtomicInteger();
        var automation = new AutomationInternalEventApplicationService(repository,
                () -> new UUID(0, sequence.incrementAndGet()).toString(), now::get);
        return new AutomationInternalEventCoordinator(automation, tasks, conversations, now::get);
    }

    private String addTrigger(AutomationTriggerSource source) {
        String id = UUID.randomUUID().toString();
        String lineage = UUID.randomUUID().toString();
        String hash = AutomationTrigger.calculateConfigSha256(
                "tenant", "owner", "agent", "Internal", "process", source.type(), source);
        repository.insertTrigger(new AutomationTrigger(
                id, lineage, 1, null, "tenant", "owner", "agent", "Internal", "process",
                source.type(), source, hash, AutomationTriggerState.ACTIVE, 1,
                UPDATED.minusSeconds(60), UPDATED.minusSeconds(30), null));
        return id;
    }

    private String subscription(String triggerId, AutomationTriggerType type) {
        AutomationTrigger trigger = repository.findTrigger("tenant", "owner", triggerId).orElseThrow();
        String id = UUID.randomUUID().toString();
        repository.insertSubscription(new AutomationTriggerPersistence.Subscription(
                id, trigger.id(), trigger.lineageId(), "tenant", "owner", type,
                "sha256:" + "a".repeat(64), AutomationTriggerPersistence.SubscriptionState.ACTIVE,
                1, UPDATED.minusSeconds(30), UPDATED.minusSeconds(30), null));
        return id;
    }

    private static TaskView projectTask(TaskState state) {
        return new TaskView("project-task", "project", null, "Task", "Goal", null,
                List.of(), List.of(), null, state, UPDATED, UPDATED);
    }

    private static TaskView chatTask(TaskState state) {
        return new TaskView("chat-task", null, null, "Task", "Goal", null,
                List.of(), List.of(), null, state, UPDATED, UPDATED,
                "CHAT", "conversation", "message");
    }

    private static ConversationView conversation(String tenant, String owner) {
        return new ConversationView("conversation", null, null, "chat-task", "chat-task",
                tenant, owner, "agent", "Conversation", ConversationStatus.ACTIVE, UPDATED, UPDATED);
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo(code));
    }
}
