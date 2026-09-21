package com.spaceagent.platform.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.automation.api.AutomationEventExecutionApplicationApi;
import com.spaceagent.platform.automation.application.AutomationEventExecutionApplicationService;
import com.spaceagent.platform.automation.domain.AutomationTrigger;
import com.spaceagent.platform.automation.domain.AutomationTriggerPersistence;
import com.spaceagent.platform.automation.domain.AutomationTriggerSource;
import com.spaceagent.platform.automation.domain.AutomationTriggerState;
import com.spaceagent.platform.automation.domain.AutomationTriggerType;
import com.spaceagent.platform.automation.infrastructure.memory.InMemoryAutomationTriggerRepository;
import com.spaceagent.platform.automation.infrastructure.memory.InMemoryAutomationDispatchPlanRepository;
import com.spaceagent.platform.automation.infrastructure.persistence.PostgresAutomationCleanupService;
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.conversation.api.ConversationView;
import com.spaceagent.platform.governance.api.GovernanceApplicationApi;
import com.spaceagent.platform.runtime.api.AgentRunView;
import com.spaceagent.platform.runtime.api.ChatExecutionView;
import com.spaceagent.platform.runtime.api.ChatRuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeCoordinationApplicationApi;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AutomationEventExecutionApplicationServiceTest {
    private static final Instant START = Instant.parse("2026-09-09T00:00:00Z");
    private InMemoryAutomationTriggerRepository repository;
    private InMemoryAutomationDispatchPlanRepository dispatchPlans;
    private GovernanceApplicationApi governance;
    private ConversationApplicationApi conversations;
    private RuntimeApplicationApi runtime;
    private RuntimeCoordinationApplicationApi coordination;
    private ChatRuntimeApplicationApi chat;
    private AtomicReference<Instant> now;
    private String occurrenceId;
    private ConversationView stableConversation;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAutomationTriggerRepository();
        dispatchPlans = new InMemoryAutomationDispatchPlanRepository();
        governance = mock(GovernanceApplicationApi.class);
        conversations = mock(ConversationApplicationApi.class);
        runtime = mock(RuntimeApplicationApi.class);
        coordination = mock(RuntimeCoordinationApplicationApi.class);
        chat = mock(ChatRuntimeApplicationApi.class);
        now = new AtomicReference<>(START);
        String triggerId = UUID.randomUUID().toString();
        String lineageId = UUID.randomUUID().toString();
        String subscriptionId = UUID.randomUUID().toString();
        var source = new AutomationTriggerSource.Webhook(
                List.of("webhook-key:test"), "HMAC_SHA256", 100, 300);
        String hash = AutomationTrigger.calculateConfigSha256(
                "tenant", "owner", "agent", "Event", "prompt", AutomationTriggerType.WEBHOOK, source);
        repository.insertTrigger(new AutomationTrigger(
                triggerId, lineageId, 1, null, "tenant", "owner", "agent", "Event", "prompt",
                AutomationTriggerType.WEBHOOK, source, hash, AutomationTriggerState.ACTIVE,
                1, START.minusSeconds(60), START.minusSeconds(30), null));
        repository.insertSubscription(new AutomationTriggerPersistence.Subscription(
                subscriptionId, triggerId, lineageId, "tenant", "owner", AutomationTriggerType.WEBHOOK,
                "sha256:" + "a".repeat(64), AutomationTriggerPersistence.SubscriptionState.ACTIVE,
                1, START, START, null));
        occurrenceId = UUID.randomUUID().toString();
        repository.createOrFindOccurrence(new AutomationTriggerPersistence.Occurrence(
                occurrenceId, triggerId, lineageId, subscriptionId, "tenant", "owner",
                "sha256:" + "b".repeat(64), "sha256:" + "c".repeat(64),
                AutomationTriggerPersistence.OccurrenceState.READY,
                START, START, 1, START, START));
        stableConversation = mock(ConversationView.class);
        when(stableConversation.id()).thenReturn(stableId("conversation"));
        when(conversations.start(any())).thenReturn(stableConversation);
        AgentRunView run = mock(AgentRunView.class);
        when(run.id()).thenReturn(stableId("run"));
        when(runtime.startRun(any())).thenReturn(run);
        RuntimeCoordinationApplicationApi.ContinuationView continuation =
                mock(RuntimeCoordinationApplicationApi.ContinuationView.class);
        when(continuation.id()).thenReturn(stableId("continuation"));
        when(coordination.enqueue(any())).thenReturn(continuation);
        when(governance.authorize(any())).thenReturn(new GovernanceApplicationApi.AuthorizationView(
                GovernanceApplicationApi.AuthorizationStatus.ALLOWED, null));
        ChatExecutionView result = mock(ChatExecutionView.class);
        when(result.waiting()).thenReturn(false);
        when(result.rootTaskId()).thenReturn("root-task");
        when(result.executionState()).thenReturn("COMPLETED");
        when(chat.executePrepared(any())).thenReturn(result);
    }

    @Test
    void governanceDispatchContinuationAndSuccessUseOneOccurrence() {
        var service = service();
        var queued = service.dispatch(new AutomationEventExecutionApplicationApi.DispatchCommand(
                occurrenceId, null));
        assertThat(queued.status()).isEqualTo("QUEUED");
        service.execute(execute(queued.deliveryId()));
        assertThat(repository.findOccurrenceById(occurrenceId).orElseThrow().state())
                .isEqualTo(AutomationTriggerPersistence.OccurrenceState.SUCCEEDED);
        assertThat(repository.findLatestDelivery(occurrenceId).orElseThrow().state())
                .isEqualTo(AutomationTriggerPersistence.DeliveryState.SUCCEEDED);
        assertThat(service.dispatch(new AutomationEventExecutionApplicationApi.DispatchCommand(
                occurrenceId, null)).status()).isEqualTo("SUCCEEDED");
        verify(runtime, times(1)).startRun(any());
        verify(chat, times(1)).executePrepared(any());
    }

    @Test
    void knownSafePreDispatchFailureBacksOffThenCreatesOnlyOneRun() {
        when(governance.authorize(any())).thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(new GovernanceApplicationApi.AuthorizationView(
                        GovernanceApplicationApi.AuthorizationStatus.ALLOWED, null));
        var service = service();
        var waiting = service.dispatch(new AutomationEventExecutionApplicationApi.DispatchCommand(
                occurrenceId, null));
        assertThat(waiting.status()).isEqualTo("RETRY_WAIT");
        assertThat(service.dispatch(new AutomationEventExecutionApplicationApi.DispatchCommand(
                occurrenceId, null)).status()).isEqualTo("RETRY_WAIT");
        now.set(START.plusSeconds(60));
        assertThat(service.dispatch(new AutomationEventExecutionApplicationApi.DispatchCommand(
                occurrenceId, null)).status()).isEqualTo("QUEUED");
        verify(runtime, times(1)).startRun(any());
        assertThat(repository.findLatestDelivery(occurrenceId).orElseThrow().attempt()).isEqualTo(2);
    }

    @Test
    void crashAfterPendingDeliveryResumesStableDispatchWithoutDuplicateRun() {
        when(conversations.start(any())).thenThrow(new IllegalStateException("crash after reserve"))
                .thenReturn(stableConversation);
        var service = service();
        var interrupted = service.dispatch(new AutomationEventExecutionApplicationApi.DispatchCommand(
                occurrenceId, null));
        assertThat(interrupted.status()).isEqualTo("DISPATCHING");
        assertThat(repository.findLatestDelivery(occurrenceId).orElseThrow().state())
                .isEqualTo(AutomationTriggerPersistence.DeliveryState.PENDING);

        var recovered = service.dispatch(new AutomationEventExecutionApplicationApi.DispatchCommand(
                occurrenceId, null));

        assertThat(recovered.status()).isEqualTo("QUEUED");
        verify(runtime, times(1)).startRun(any());
        verify(coordination, times(1)).enqueue(any());
        var plan = dispatchPlans.findByOccurrence("tenant", "owner", occurrenceId).orElseThrow();
        assertThat(recovered.dispatchRunId()).isEqualTo(plan.dispatchRunId());
        assertThat(recovered.continuationId()).isEqualTo(plan.continuationId());
    }

    @Test
    void approvalBlocksBeforeRunAndApprovedRetryCanDispatch() {
        var requested = mock(GovernanceApplicationApi.ApprovalView.class);
        when(requested.id()).thenReturn("00000000-0000-0000-0000-000000000099");
        when(governance.authorize(any())).thenReturn(new GovernanceApplicationApi.AuthorizationView(
                GovernanceApplicationApi.AuthorizationStatus.APPROVAL_REQUIRED, requested))
                .thenReturn(new GovernanceApplicationApi.AuthorizationView(
                        GovernanceApplicationApi.AuthorizationStatus.ALLOWED, null));
        var service = service();
        assertThat(service.dispatch(new AutomationEventExecutionApplicationApi.DispatchCommand(
                occurrenceId, null)).status()).isEqualTo("BLOCKED");
        verifyNoInteractions(runtime);
        var plan = dispatchPlans.findByOccurrence("tenant", "owner", occurrenceId).orElseThrow();
        var approved = mock(GovernanceApplicationApi.ApprovalView.class);
        when(approved.state()).thenReturn(com.spaceagent.platform.governance.domain.ApprovalState.APPROVED);
        when(approved.actionType()).thenReturn(
                com.spaceagent.platform.governance.domain.GovernanceActionType.AUTOMATION_TRIGGER);
        when(approved.operationHash()).thenReturn(plan.operationHash());
        when(governance.getApproval(any())).thenReturn(approved);
        long revision = repository.findOccurrenceById(occurrenceId).orElseThrow().revision();
        assertThat(service.resumeApproval(new AutomationEventExecutionApplicationApi.ResumeApprovalCommand(
                "tenant", "owner", occurrenceId, requested.id(), revision)).status())
                .isEqualTo("QUEUED");
    }

    @Test
    void postDispatchFailureBecomesUnknownAndNeverCreatesAnotherRun() {
        var service = service();
        var queued = service.dispatch(new AutomationEventExecutionApplicationApi.DispatchCommand(
                occurrenceId, null));
        when(chat.executePrepared(any())).thenThrow(new IllegalStateException("ambiguous provider result"));
        assertThatThrownBy(() -> service.execute(execute(queued.deliveryId())))
                .isInstanceOf(IllegalStateException.class);
        var replay = service.dispatch(new AutomationEventExecutionApplicationApi.DispatchCommand(
                occurrenceId, null));
        assertThat(replay.status()).isEqualTo("BLOCKED");
        assertThat(replay.safeCode()).isEqualTo("AUTOMATION_EVENT_EFFECT_UNKNOWN");
        verify(runtime, times(1)).startRun(any());
        verify(chat, times(1)).executePrepared(any());
    }

    @Test
    void exhaustedKnownSafePreDispatchRetriesCreateOneDeadLetterWithoutRun() {
        when(governance.authorize(any())).thenThrow(new IllegalStateException("database unavailable"));
        var service = service();
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(service.dispatch(new AutomationEventExecutionApplicationApi.DispatchCommand(
                    occurrenceId, null)).status()).isEqualTo("RETRY_WAIT");
            now.set(now.get().plusSeconds(60));
        }
        assertThat(service.dispatch(new AutomationEventExecutionApplicationApi.DispatchCommand(
                occurrenceId, null)).status()).isEqualTo("DEAD_LETTERED");
        assertThat(repository.findOccurrenceById(occurrenceId).orElseThrow().state())
                .isEqualTo(AutomationTriggerPersistence.OccurrenceState.DEAD_LETTERED);
        verifyNoInteractions(runtime, chat);
    }

    @Test
    void cleanupBlocksUnknownAndDeletesTriggerTablesBeforeLegacyRows() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any(), any())).thenReturn(1L);
        var cleanup = new PostgresAutomationCleanupService(jdbc);
        assertThat(cleanup.cleanupUser("owner").blocked()).isTrue();
        verify(jdbc, never()).update(startsWith("DELETE"),
                org.mockito.ArgumentMatchers.<Object[]>any());

        reset(jdbc);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any(), any())).thenReturn(0L);
        assertThat(cleanup.cleanupUser("owner").blocked()).isFalse();
        var order = inOrder(jdbc);
        order.verify(jdbc).update(contains("trigger_dead_letters"), eq("owner"));
        order.verify(jdbc).update(contains("dispatch_plans"), eq("owner"));
        order.verify(jdbc).update(contains("trigger_deliveries"), eq("owner"));
        order.verify(jdbc).update(contains("trigger_occurrences"), eq("owner"));
        order.verify(jdbc).update(contains("trigger_subscriptions"), eq("owner"));
        order.verify(jdbc).update(contains("automation_triggers"), eq("owner"));
    }

    private AutomationEventExecutionApplicationService service() {
        AtomicInteger sequence = new AtomicInteger(100);
        var service = new AutomationEventExecutionApplicationService(
                repository, governance, conversations, runtime, coordination, chat,
                () -> new UUID(0, sequence.incrementAndGet()).toString(), now::get, new ObjectMapper());
        service.setDispatchPlans(dispatchPlans);
        return service;
    }

    private AutomationEventExecutionApplicationApi.ExecuteCommand execute(String deliveryId) {
        var plan = dispatchPlans.findByOccurrence("tenant", "owner", occurrenceId).orElseThrow();
        return new AutomationEventExecutionApplicationApi.ExecuteCommand(
                occurrenceId, deliveryId, plan.dispatchRunId(), plan.conversationId(), "config-hash",
                UUID.randomUUID().toString(), 1, now.get().plusSeconds(60));
    }

    private String stableId(String kind) {
        return UUID.nameUUIDFromBytes(("automation:" + kind + ":" + occurrenceId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
}
