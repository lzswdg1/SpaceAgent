package com.spaceagent.platform.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.api.AgentApplicationApi;
import com.spaceagent.platform.agent.api.AgentDefinitionView;
import com.spaceagent.platform.agent.domain.AgentDefinitionStatus;
import com.spaceagent.platform.automation.api.AutomationApplicationApi;
import com.spaceagent.platform.automation.application.AutomationApplicationService;
import com.spaceagent.platform.automation.application.AutomationExecutionCoordinator;
import com.spaceagent.platform.automation.application.AutomationScheduleCalculator;
import com.spaceagent.platform.automation.infrastructure.memory.InMemoryAutomationRepository;
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.conversation.api.ConversationView;
import com.spaceagent.platform.conversation.domain.ConversationStatus;
import com.spaceagent.platform.governance.api.GovernanceApplicationApi;
import com.spaceagent.platform.governance.domain.ApprovalState;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.api.TenantMembershipView;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.runtime.api.AgentRunView;
import com.spaceagent.platform.runtime.api.ChatExecutionView;
import com.spaceagent.platform.runtime.api.ChatRuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeCoordinationApplicationApi;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationState;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationType;
import com.spaceagent.platform.runtime.domain.orchestration.ExecutionCursor;
import com.spaceagent.shared.time.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformAutomationApplicationTest {

    private MutableTime time;
    private InMemoryAutomationRepository repository;
    private RuntimeApplicationApi runtime;
    private RuntimeCoordinationApplicationApi coordination;
    private ChatRuntimeApplicationApi chat;
    private AutomationApplicationService service;
    private AtomicBoolean approvalRequired;
    private AtomicBoolean approved;

    @BeforeEach
    void setUp() {
        time = new MutableTime(Instant.parse("2026-08-23T12:00:00Z"));
        repository = new InMemoryAutomationRepository(time);
        IdentityApplicationApi identity = mock(IdentityApplicationApi.class);
        when(identity.findTenantMembership("tenant", "owner"))
                .thenReturn(Optional.of(new TenantMembershipView(
                        "tenant", "owner", TenantRole.OWNER,
                        TenantMembershipStatus.ACTIVE, time.now(), time.now())));
        AgentApplicationApi agents = mock(AgentApplicationApi.class);
        when(agents.findById("agent")).thenReturn(Optional.of(agent()));
        GovernanceApplicationApi governance = mock(GovernanceApplicationApi.class);
        approvalRequired = new AtomicBoolean();
        approved = new AtomicBoolean();
        when(governance.authorize(any())).thenAnswer(invocation -> {
            GovernanceApplicationApi.AuthorizeCommand command = invocation.getArgument(0);
            if (!approvalRequired.get()) {
                return new GovernanceApplicationApi.AuthorizationView(
                        GovernanceApplicationApi.AuthorizationStatus.ALLOWED, null);
            }
            var approval = approval(command, approved.get()
                    ? ApprovalState.CONSUMED : ApprovalState.PENDING);
            return new GovernanceApplicationApi.AuthorizationView(
                    approved.get()
                            ? GovernanceApplicationApi.AuthorizationStatus.ALLOWED
                            : GovernanceApplicationApi.AuthorizationStatus.APPROVAL_REQUIRED,
                    approval);
        });
        ConversationApplicationApi conversations = mock(ConversationApplicationApi.class);
        when(conversations.start(any())).thenReturn(new ConversationView(
                "conversation", null, null, null, "tenant", "owner", "agent",
                "Automation", ConversationStatus.ACTIVE, time.now(), time.now()));
        runtime = mock(RuntimeApplicationApi.class);
        when(runtime.startRun(any())).thenAnswer(invocation -> run());
        when(runtime.resumeFenced(any())).thenAnswer(invocation -> runInProgress());
        coordination = mock(RuntimeCoordinationApplicationApi.class);
        when(coordination.enqueue(any())).thenAnswer(invocation -> continuation());
        chat = mock(ChatRuntimeApplicationApi.class);
        when(chat.executePrepared(any())).thenReturn(chatResult());
        var coordinator = new AutomationExecutionCoordinator(
                repository, governance, conversations, runtime,
                coordination, chat, () -> UUID.randomUUID().toString(), new ObjectMapper());
        service = new AutomationApplicationService(
                repository, new AutomationScheduleCalculator(), coordinator,
                identity, agents, () -> UUID.randomUUID().toString());
    }

    @Test
    void periodicScheduleUsesSpringCronAndManualTriggerIsIdempotent() {
        var schedule = service.create(periodic());
        assertThat(schedule.cronExpr()).isEqualTo("0 * * * * *");
        assertThat(schedule.nextRunAt()).isEqualTo(Instant.parse("2026-08-23T12:01:00Z"));

        var paused = service.pause(actor(schedule.id()));
        assertThat(paused.status()).isEqualTo("paused");
        assertThat(service.resume(actor(schedule.id())).status()).isEqualTo("active");

        var first = service.trigger(trigger(schedule.id(), "request-1"));
        var replay = service.trigger(trigger(schedule.id(), "request-1"));
        assertThat(first.id()).isEqualTo(replay.id());
        assertThat(first.status()).isEqualTo("queued");
        verify(coordination).enqueue(any());
    }

    @Test
    void governanceWaitsWithoutCreatingRunThenDispatchesApprovedOccurrence() {
        approvalRequired.set(true);
        var schedule = service.create(periodic());
        var waiting = service.trigger(trigger(schedule.id(), "approval-request"));
        assertThat(waiting.status()).isEqualTo("waiting_approval");
        assertThat(waiting.approvalId()).isEqualTo("approval");
        verify(runtime, never()).startRun(any());

        approved.set(true);
        assertThat(service.dispatchNextApproved()).isTrue();
        var queued = service.executions(
                "tenant", "owner", "agent", schedule.id(), 10).getFirst();
        assertThat(queued.status()).isEqualTo("queued");
        assertThat(queued.agentRunId()).isEqualTo("dispatch-run");
        verify(runtime).startRun(any());
    }

    @Test
    void archivedScheduleCancelsAnUnreleasedApprovalOccurrence() {
        approvalRequired.set(true);
        var schedule = service.create(periodic());
        service.trigger(trigger(schedule.id(), "archive-before-approval"));
        service.archive(actor(schedule.id()));
        approved.set(true);

        assertThat(service.dispatchNextApproved()).isTrue();
        assertThat(repository.findExecutions(schedule.id(), 10).getFirst().state().name())
                .isEqualTo("CANCELLED");
        verify(runtime, never()).startRun(any());
    }

    @Test
    void oneTimeDueOccurrenceMaterializesOnceAndPreparedRunExecutesOnce() {
        var schedule = service.create(new AutomationApplicationApi.CreateScheduleCommand(
                "tenant", "owner", "agent", "one time", "summarize", "one_time",
                null, time.now().plusSeconds(10), "UTC", 1));
        time.advanceSeconds(11);
        assertThat(service.materializeNextDue()).isTrue();
        assertThat(service.materializeNextDue()).isFalse();
        assertThat(service.get("tenant", "owner", "agent", schedule.id()).status())
                .isEqualTo("completed");

        var queued = service.executions(
                "tenant", "owner", "agent", schedule.id(), 10).getFirst();
        service.executeDispatched(new AutomationApplicationApi.ExecuteDispatchedCommand(
                queued.id(), "dispatch-run", "lease", 1));
        var succeeded = service.executions(
                "tenant", "owner", "agent", schedule.id(), 10).getFirst();
        assertThat(succeeded.status()).isEqualTo("success");
        assertThat(succeeded.tokenUsage().inputTokens()).isEqualTo(10);
        verify(chat).executePrepared(any());

        service.executeDispatched(new AutomationApplicationApi.ExecuteDispatchedCommand(
                queued.id(), "dispatch-run", "lease", 1));
        verify(chat).executePrepared(any());
    }

    @Test
    void preparedFailureIsDurableAndNotBlindlyRetried() {
        var schedule = service.create(periodic());
        var queued = service.trigger(trigger(schedule.id(), "failure"));
        when(chat.executePrepared(any())).thenThrow(new IllegalStateException("provider down"));

        assertThatThrownBy(() -> service.executeDispatched(
                new AutomationApplicationApi.ExecuteDispatchedCommand(
                        queued.id(), "dispatch-run", "lease", 1)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.executions(
                "tenant", "owner", "agent", schedule.id(), 10).getFirst().status())
                .isEqualTo("failed");
        service.executeDispatched(new AutomationApplicationApi.ExecuteDispatchedCommand(
                queued.id(), "dispatch-run", "lease", 1));
        verify(chat).executePrepared(any());
    }

    @Test
    void invalidCronAndPastOneTimeAreRejected() {
        assertThatThrownBy(() -> service.create(new AutomationApplicationApi.CreateScheduleCommand(
                "tenant", "owner", "agent", "bad", "prompt", "periodic",
                "not cron", null, "UTC", 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(new AutomationApplicationApi.CreateScheduleCommand(
                "tenant", "owner", "agent", "past", "prompt", "one_time",
                null, time.now(), "UTC", 1))).isInstanceOf(IllegalArgumentException.class);
    }

    private AutomationApplicationApi.CreateScheduleCommand periodic() {
        return new AutomationApplicationApi.CreateScheduleCommand(
                "tenant", "owner", "agent", "periodic", "summarize", "periodic",
                "* * * * *", null, "UTC", 1);
    }

    private AutomationApplicationApi.ActorScheduleCommand actor(String scheduleId) {
        return new AutomationApplicationApi.ActorScheduleCommand(
                "tenant", "owner", "agent", scheduleId);
    }

    private AutomationApplicationApi.TriggerCommand trigger(String scheduleId, String key) {
        return new AutomationApplicationApi.TriggerCommand(
                "tenant", "owner", "agent", scheduleId, key, null);
    }

    private AgentDefinitionView agent() {
        return new AgentDefinitionView(
                "agent", "owner", "tenant", "Agent", null,
                AgentDefinitionStatus.ACTIVE, "system", null,
                "provider", "model", 0.2, 2048, 10, "private",
                true, false, false, List.of(), List.of(), List.of(),
                1, time.now(), time.now(), null);
    }

    private GovernanceApplicationApi.ApprovalView approval(
            GovernanceApplicationApi.AuthorizeCommand command, ApprovalState state) {
        return new GovernanceApplicationApi.ApprovalView(
                "approval", "tenant", "owner", command.actionType(), command.resourceType(),
                command.resourceId(), command.operationHash(), command.summary(), state,
                time.now().plusSeconds(3600), state == ApprovalState.PENDING ? null : "admin",
                state == ApprovalState.PENDING ? null : time.now(), null,
                state == ApprovalState.CONSUMED ? time.now() : null, 1, time.now(), time.now());
    }

    private AgentRunView run() {
        return new AgentRunView(
                "dispatch-run", "agent", "version", "tenant", "owner", "conversation",
                null, null, null, null, ExecutionCursor.initial(), 0, AgentRunState.QUEUED,
                null, time.now(), time.now(), null);
    }

    private AgentRunView runInProgress() {
        AgentRunView value = run();
        return new AgentRunView(
                value.id(), value.agentId(), value.configurationSnapshotId(), value.tenantId(),
                value.ownerId(), value.conversationId(), null, null, null, null,
                value.executionCursor(), 1, AgentRunState.IN_PROGRESS, null,
                value.createdAt(), time.now(), null);
    }

    private RuntimeCoordinationApplicationApi.ContinuationView continuation() {
        return new RuntimeCoordinationApplicationApi.ContinuationView(
                "continuation", "dispatch-run", RuntimeContinuationType.AUTOMATION_EXECUTION,
                "automation", "{}", RuntimeContinuationState.PENDING, time.now(),
                0, 1, null, null, null, null, 0, null, time.now(), time.now(), null);
    }

    private ChatExecutionView chatResult() {
        return new ChatExecutionView(
                "conversation", "dispatch-run", "done", false, null,
                0, List.of(), false, 0, List.of(), 10, 5, List.of());
    }

    private static final class MutableTime implements TimeProvider {
        private Instant now;

        private MutableTime(Instant now) {
            this.now = now;
        }

        @Override
        public Instant now() {
            return now;
        }

        private void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }
    }
}
