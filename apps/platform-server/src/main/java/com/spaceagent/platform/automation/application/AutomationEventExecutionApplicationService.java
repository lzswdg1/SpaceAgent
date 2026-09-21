package com.spaceagent.platform.automation.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.api.AgentCurrentConfigurationApplicationApi;
import com.spaceagent.platform.automation.api.AutomationEventExecutionApplicationApi;
import com.spaceagent.platform.automation.domain.AutomationDispatchPlan;
import com.spaceagent.platform.automation.domain.AutomationDispatchPlanRepository;
import com.spaceagent.platform.automation.domain.AutomationTriggerPersistence;
import com.spaceagent.platform.automation.domain.AutomationTriggerRepository;
import com.spaceagent.platform.automation.domain.AutomationTriggerState;
import com.spaceagent.platform.conversation.api.ConversationApplicationApi;
import com.spaceagent.platform.conversation.api.StartConversationCommand;
import com.spaceagent.platform.governance.api.GovernanceApplicationApi;
import com.spaceagent.platform.governance.domain.ApprovalState;
import com.spaceagent.platform.governance.domain.GovernanceActionType;
import com.spaceagent.platform.runtime.api.ChatRuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.PreparedChatExecutionCommand;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeCoordinationApplicationApi;
import com.spaceagent.platform.runtime.api.StartAgentRunCommand;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationType;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AutomationEventExecutionApplicationService
        implements AutomationEventExecutionApplicationApi {
    private static final int MAX_PRE_DISPATCH_ATTEMPTS = 3;
    private static final int RETRY_SECONDS = 60;
    private final AutomationTriggerRepository repository;
    private final GovernanceApplicationApi governance;
    private final ConversationApplicationApi conversations;
    private final RuntimeApplicationApi runtime;
    private final RuntimeCoordinationApplicationApi coordination;
    private final ChatRuntimeApplicationApi chat;
    private final IdGenerator ids;
    private final TimeProvider time;
    private final ObjectMapper json;
    private AutomationDispatchPlanRepository dispatchPlans;
    private AgentCurrentConfigurationApplicationApi currentConfigurations;

    public AutomationEventExecutionApplicationService(
            AutomationTriggerRepository repository,
            GovernanceApplicationApi governance, ConversationApplicationApi conversations,
            RuntimeApplicationApi runtime, RuntimeCoordinationApplicationApi coordination,
            ChatRuntimeApplicationApi chat, IdGenerator ids, TimeProvider time,
            ObjectMapper json) {
        this.repository = repository;
        this.governance = governance;
        this.conversations = conversations;
        this.runtime = runtime;
        this.coordination = coordination;
        this.chat = chat;
        this.ids = ids;
        this.time = time;
        this.json = json;
    }

    @Autowired
    public void setDispatchPlans(AutomationDispatchPlanRepository dispatchPlans) {
        this.dispatchPlans = dispatchPlans;
    }

    @Autowired(required=false)
    public void setCurrentConfigurations(AgentCurrentConfigurationApplicationApi currentConfigurations) {
        this.currentConfigurations = currentConfigurations;
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DispatchView dispatch(DispatchCommand command) {
        var occurrence = occurrence(command.occurrenceId());
        if (command.expectedOccurrenceRevision() != null
                && (occurrence.state() != AutomationTriggerPersistence.OccurrenceState.DISPATCHING
                || occurrence.revision() != command.expectedOccurrenceRevision())) throw conflict();
        if (occurrence.state() == AutomationTriggerPersistence.OccurrenceState.UNKNOWN) {
            return view(occurrence, repository.findLatestDelivery(occurrence.id()).orElse(null),
                    "BLOCKED", null, "AUTOMATION_EVENT_EFFECT_UNKNOWN");
        }
        if (occurrence.state() == AutomationTriggerPersistence.OccurrenceState.BLOCKED) {
            return view(occurrence, repository.findLatestDelivery(occurrence.id()).orElse(null),
                    "BLOCKED", null, "AUTOMATION_APPROVAL_REQUIRED");
        }
        if (terminal(occurrence.state())
                || occurrence.state() == AutomationTriggerPersistence.OccurrenceState.DISPATCHED) {
            return view(occurrence, repository.findLatestDelivery(occurrence.id()).orElse(null),
                    occurrence.state().name(), null, null);
        }
        Instant now = time.now();
        var latest = repository.findLatestDelivery(occurrence.id()).orElse(null);
        if (latest != null && latest.state() == AutomationTriggerPersistence.DeliveryState.CLAIMED) {
            return view(occurrence, latest, "CLAIMED", null, null);
        }
        if (latest != null && latest.nextAttemptAt() != null && now.isBefore(latest.nextAttemptAt())) {
            return view(occurrence, latest, "RETRY_WAIT", latest.nextAttemptAt(), latest.safeErrorCode());
        }
        if (latest != null && latest.state() == AutomationTriggerPersistence.DeliveryState.FAILED
                && latest.nextAttemptAt() == null
                && occurrence.state() == AutomationTriggerPersistence.OccurrenceState.FAILED) {
            return view(occurrence, latest, "FAILED", null, latest.safeErrorCode());
        }
        AutomationTriggerPersistence.Delivery delivery = latest;
        if (delivery == null || delivery.state() != AutomationTriggerPersistence.DeliveryState.PENDING) {
            int attempt = latest == null ? 1 : latest.attempt() + 1;
            if (attempt > MAX_PRE_DISPATCH_ATTEMPTS) {
                var dead = new AutomationTriggerPersistence.DeadLetter(
                        ids.nextId(), occurrence.id(), latest.id(), occurrence.tenantId(),
                        occurrence.ownerId(), "PRE_DISPATCH_RETRY_EXHAUSTED",
                        latest.evidenceSha256(), now);
                repository.createOrFindDeadLetter(dead);
                var ended = transition(occurrence,
                        AutomationTriggerPersistence.OccurrenceState.DEAD_LETTERED, now);
                return view(ended, latest, "DEAD_LETTERED", null, dead.reasonCode());
            }
            delivery = repository.createOrFindDelivery(new AutomationTriggerPersistence.Delivery(
                    ids.nextId(), occurrence.id(), occurrence.tenantId(), occurrence.ownerId(),
                    attempt, AutomationTriggerPersistence.DeliveryState.PENDING, null, null,
                    null, null, 0, null, null, 1, now, now));
        }
        var trigger = repository.findTrigger(
                occurrence.tenantId(), occurrence.ownerId(), occurrence.triggerVersionId()).orElseThrow();
        if (trigger.state() != AutomationTriggerState.ACTIVE) {
            var blocked = delivery(delivery, AutomationTriggerPersistence.DeliveryState.FAILED,
                    null, null, 0, "AUTOMATION_TRIGGER_NOT_ACTIVE", trigger.configSha256(), now);
            repository.updateDelivery(blocked, delivery.revision());
            return view(transition(occurrence, AutomationTriggerPersistence.OccurrenceState.BLOCKED, now),
                    blocked, "BLOCKED", null, blocked.safeErrorCode());
        }
        var current = currentConfigurations == null ? null : currentConfigurations.requireCurrent(
                trigger.tenantId(), trigger.ownerId(), trigger.agentId());
        String operationHash = sha256(String.join("\n", occurrence.id(), trigger.id(),
                trigger.configSha256(), current == null
                        ? "NON_SPRING_TEST_CONFIGURATION" : current.configHash()));
        AutomationDispatchPlan plan = plans().createOrFind(new AutomationDispatchPlan(
                stableId("plan", occurrence.id()), occurrence.id(), delivery.id(),
                occurrence.tenantId(), occurrence.ownerId(), operationHash, null,
                stableId("conversation", occurrence.id()), stableId("run", occurrence.id()),
                stableId("continuation", occurrence.id()), AutomationDispatchPlan.Phase.RESERVED,
                null, 1, now, now));
        if (!plan.operationHash().equals(operationHash)) {
            return markDispatchUnknown(occurrence, delivery, plan,
                    "AUTOMATION_DISPATCH_PLAN_CONFLICT");
        }
        if (!plan.deliveryId().equals(delivery.id())) {
            try {
                plan = updatePlan(plan.retryDelivery(delivery.id(), time.now()), plan);
            } catch (RuntimeException unsafeRetry) {
                return markDispatchUnknown(occurrence, delivery, plan,
                        "AUTOMATION_DISPATCH_PLAN_CONFLICT");
            }
        }
        GovernanceApplicationApi.AuthorizationView authorization;
        try {
            authorization = governance.authorize(new GovernanceApplicationApi.AuthorizeCommand(
                    trigger.tenantId(), trigger.ownerId(), GovernanceActionType.AUTOMATION_TRIGGER,
                    "AUTOMATION_TRIGGER", trigger.id(), operationHash,
                    "Trigger event automation: " + trigger.description(),
                    command.approvalId() == null ? plan.approvalId() : command.approvalId()));
        } catch (RuntimeException unavailable) {
            var failed = delivery(delivery, AutomationTriggerPersistence.DeliveryState.FAILED,
                    now.plusSeconds(RETRY_SECONDS), null, 0,
                    "AUTOMATION_GOVERNANCE_UNAVAILABLE", operationHash, now);
            repository.updateDelivery(failed, delivery.revision());
            return view(occurrence, failed, "RETRY_WAIT", failed.nextAttemptAt(), failed.safeErrorCode());
        }
        if (!authorization.allowed()) {
            boolean waiting = authorization.status()
                    == GovernanceApplicationApi.AuthorizationStatus.APPROVAL_REQUIRED;
            String code = waiting ? "AUTOMATION_APPROVAL_REQUIRED" : "AUTOMATION_APPROVAL_REJECTED";
            var failed = delivery(delivery, AutomationTriggerPersistence.DeliveryState.FAILED,
                    null, null, 0, code, operationHash, now);
            repository.updateDelivery(failed, delivery.revision());
            String approvalId = authorization.approval() == null ? null : authorization.approval().id();
            updatePlan(plan.block(approvalId, code, now), plan);
            var state = waiting ? AutomationTriggerPersistence.OccurrenceState.BLOCKED
                    : AutomationTriggerPersistence.OccurrenceState.FAILED;
            return view(transition(occurrence, state, now), failed, state.name(), null, code);
        }
        try {
            return materialize(occurrence, delivery, plan, trigger, null);
        } catch (BusinessException stableConflict) {
            if (stableConflict.getCode() != null
                    && stableConflict.getCode().startsWith("AUTOMATION_")
                    && stableConflict.getCode().endsWith("_ID_CONFLICT")) {
                return markDispatchUnknown(occurrence, delivery, plan, stableConflict.getCode());
            }
            return recoveryPending(occurrence, delivery, plan, now);
        } catch (RuntimeException recoverable) {
            return recoveryPending(occurrence, delivery, plan, now);
        }
    }

    private DispatchView materialize(
            AutomationTriggerPersistence.Occurrence occurrence,
            AutomationTriggerPersistence.Delivery delivery, AutomationDispatchPlan original,
            com.spaceagent.platform.automation.domain.AutomationTrigger trigger,
            String configurationHash) {
        AutomationDispatchPlan plan = original;
        var conversation = conversations.start(new StartConversationCommand(
                null, null, null, null, trigger.tenantId(), trigger.ownerId(), trigger.agentId(),
                abbreviate("Automation: " + trigger.description(), 120), plan.conversationId()));
        requireStable(plan.conversationId(), conversation.id(), "AUTOMATION_CONVERSATION_ID_CONFLICT");
        if (plan.phase() == AutomationDispatchPlan.Phase.RESERVED) {
            plan = updatePlan(plan.advance(AutomationDispatchPlan.Phase.CONVERSATION_READY, time.now()), plan);
        }
        var run = runtime.startRun(new StartAgentRunCommand(
                trigger.tenantId(), trigger.ownerId(), trigger.agentId(), null,
                conversation.id(), null, null, null, null, null, null, null,
                plan.dispatchRunId()));
        requireStable(plan.dispatchRunId(), run.id(), "AUTOMATION_RUN_ID_CONFLICT");
        if (plan.phase().ordinal() < AutomationDispatchPlan.Phase.RUN_READY.ordinal()) {
            plan = updatePlan(plan.advance(AutomationDispatchPlan.Phase.RUN_READY, time.now()), plan);
        }
        String payload;
        try {
            payload = json.writeValueAsString(Map.of(
                    "eventOccurrenceId", occurrence.id(), "deliveryId", delivery.id(),
                    "conversationId", conversation.id()));
        } catch (Exception exception) {
            throw new IllegalStateException("Automation continuation payload invalid", exception);
        }
        var continuation = coordination.enqueue(
                new RuntimeCoordinationApplicationApi.EnqueueContinuationCommand(
                        run.id(), RuntimeContinuationType.AUTOMATION_EXECUTION,
                        "automation-event:" + occurrence.id(), payload, null, 1,
                        plan.continuationId()));
        requireStable(plan.continuationId(), continuation.id(),
                "AUTOMATION_CONTINUATION_ID_CONFLICT");
        if (plan.phase().ordinal() < AutomationDispatchPlan.Phase.CONTINUATION_READY.ordinal()) {
            plan = updatePlan(plan.advance(
                    AutomationDispatchPlan.Phase.CONTINUATION_READY, time.now()), plan);
        }
        var dispatched = transition(
                occurrence, AutomationTriggerPersistence.OccurrenceState.DISPATCHED, time.now());
        return new DispatchView(dispatched.id(), delivery.id(), "QUEUED", run.id(),
                continuation.id(), null, null);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DispatchView resumeApproval(ResumeApprovalCommand command) {
        var occurrence = repository.findOccurrence(
                        command.tenantId(), command.ownerId(), command.occurrenceId())
                .orElseThrow(() -> new BusinessException("Automation occurrence not found",
                        HttpStatus.NOT_FOUND, "AUTOMATION_EVENT_OCCURRENCE_NOT_FOUND"));
        if (occurrence.state() != AutomationTriggerPersistence.OccurrenceState.BLOCKED
                || occurrence.revision() != command.expectedOccurrenceRevision()) throw conflict();
        var plan = plans().findByOccurrence(
                command.tenantId(), command.ownerId(), occurrence.id()).orElseThrow(this::conflict);
        var approval = governance.getApproval(new GovernanceApplicationApi.ApprovalQuery(
                command.tenantId(), command.ownerId(), command.approvalId()));
        if (approval.state() != ApprovalState.APPROVED
                || approval.actionType() != GovernanceActionType.AUTOMATION_TRIGGER
                || !approval.operationHash().equals(plan.operationHash())) {
            throw new BusinessException("Automation approval does not match dispatch",
                    HttpStatus.CONFLICT, "AUTOMATION_APPROVAL_MISMATCH");
        }
        var currentDelivery = repository.findLatestDelivery(occurrence.id()).orElseThrow(this::conflict);
        if (currentDelivery.state() != AutomationTriggerPersistence.DeliveryState.FAILED
                || !"AUTOMATION_APPROVAL_REQUIRED".equals(currentDelivery.safeErrorCode())) throw conflict();
        updatePlan(plan.resume(command.approvalId(), time.now()), plan);
        var pending = delivery(currentDelivery, AutomationTriggerPersistence.DeliveryState.PENDING,
                null, null, currentDelivery.fencingToken(), null, plan.operationHash(), time.now());
        repository.updateDelivery(pending, currentDelivery.revision()).orElseThrow(this::conflict);
        var dispatching = transition(
                occurrence, AutomationTriggerPersistence.OccurrenceState.DISPATCHING, time.now());
        return dispatch(new DispatchCommand(
                occurrence.id(), command.approvalId(), dispatching.revision()));
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void execute(ExecuteCommand command) {
        var occurrence = occurrence(command.occurrenceId());
        var current = repository.findDelivery(
                        occurrence.tenantId(), occurrence.ownerId(), command.deliveryId())
                .orElseThrow();
        if (terminal(occurrence.state())) return;
        if (occurrence.state() == AutomationTriggerPersistence.OccurrenceState.UNKNOWN
                || current.state() == AutomationTriggerPersistence.DeliveryState.CLAIMED) {
            markUnknown(occurrence, current, "AUTOMATION_EVENT_EFFECT_UNKNOWN", command);
            return;
        }
        if (occurrence.state() != AutomationTriggerPersistence.OccurrenceState.DISPATCHED
                || current.state() != AutomationTriggerPersistence.DeliveryState.PENDING) throw conflict();
        var claimed = delivery(current, AutomationTriggerPersistence.DeliveryState.CLAIMED,
                null, command.leaseToken(), command.fencingToken(), null, null, time.now(),
                command.leaseUntil());
        claimed = repository.updateDelivery(claimed, current.revision()).orElseThrow(this::conflict);
        var trigger = repository.findTrigger(
                occurrence.tenantId(), occurrence.ownerId(), occurrence.triggerVersionId()).orElseThrow();
        try {
            var result = chat.executePrepared(new PreparedChatExecutionCommand(
                    occurrence.tenantId(), occurrence.ownerId(), command.conversationId(),
                    trigger.agentId(), command.dispatchRunId(), command.dispatchRunId(), trigger.prompt()));
            if (result.waiting()) throw new IllegalStateException("Automation result is non-terminal");
            String evidence = sha256(String.join("\n", command.dispatchRunId(),
                    result.rootTaskId() == null ? "none" : result.rootTaskId(), result.executionState()));
            var succeeded = delivery(claimed, AutomationTriggerPersistence.DeliveryState.SUCCEEDED,
                    null, null, claimed.fencingToken(), null, evidence, time.now());
            repository.updateDelivery(succeeded, claimed.revision()).orElseThrow(this::conflict);
            transition(occurrence, AutomationTriggerPersistence.OccurrenceState.SUCCEEDED, time.now());
        } catch (RuntimeException error) {
            markUnknown(occurrence, claimed, "AUTOMATION_EVENT_EFFECT_UNKNOWN", command);
            throw error;
        }
    }

    private DispatchView markDispatchUnknown(
            AutomationTriggerPersistence.Occurrence occurrence,
            AutomationTriggerPersistence.Delivery delivery, AutomationDispatchPlan plan, String code) {
        var unknownPlan = plan.phase() == AutomationDispatchPlan.Phase.UNKNOWN
                ? plan : updatePlan(plan.unknown(code, time.now()), plan);
        var unknown = delivery(delivery, AutomationTriggerPersistence.DeliveryState.UNKNOWN,
                null, null, delivery.fencingToken(), code, plan.operationHash(), time.now());
        repository.updateDelivery(unknown, delivery.revision());
        var occurrenceUnknown = occurrence.state() == AutomationTriggerPersistence.OccurrenceState.UNKNOWN
                ? occurrence : transition(occurrence,
                AutomationTriggerPersistence.OccurrenceState.UNKNOWN, time.now());
        return new DispatchView(occurrenceUnknown.id(), unknown.id(), "BLOCKED",
                unknownPlan.dispatchRunId(), unknownPlan.continuationId(), null, code);
    }

    private DispatchView recoveryPending(
            AutomationTriggerPersistence.Occurrence occurrence,
            AutomationTriggerPersistence.Delivery delivery, AutomationDispatchPlan plan, Instant now) {
        return new DispatchView(occurrence.id(), delivery.id(), "DISPATCHING",
                plan.dispatchRunId(), plan.continuationId(), now.plusSeconds(RETRY_SECONDS),
                "AUTOMATION_DISPATCH_RECOVERY_PENDING");
    }

    private void markUnknown(
            AutomationTriggerPersistence.Occurrence occurrence,
            AutomationTriggerPersistence.Delivery delivery, String code, ExecuteCommand command) {
        var unknown = delivery(delivery, AutomationTriggerPersistence.DeliveryState.UNKNOWN,
                null, null, command.fencingToken(), code, sha256(command.dispatchRunId()), time.now());
        repository.updateDelivery(unknown, delivery.revision());
        if (occurrence.state() != AutomationTriggerPersistence.OccurrenceState.UNKNOWN) {
            transition(occurrence, AutomationTriggerPersistence.OccurrenceState.UNKNOWN, time.now());
        }
    }

    private AutomationDispatchPlan updatePlan(
            AutomationDispatchPlan next, AutomationDispatchPlan current) {
        return plans().update(next, current.revision()).orElseThrow(this::conflict);
    }

    private AutomationDispatchPlanRepository plans() {
        if (dispatchPlans == null) throw new BusinessException(
                "Automation dispatch recovery is unavailable", HttpStatus.SERVICE_UNAVAILABLE,
                "AUTOMATION_DISPATCH_RECOVERY_UNAVAILABLE");
        return dispatchPlans;
    }

    private AutomationTriggerPersistence.Occurrence occurrence(String id) {
        return repository.findOccurrenceById(id).orElseThrow(() -> new BusinessException(
                "Automation event occurrence not found", HttpStatus.NOT_FOUND,
                "AUTOMATION_EVENT_OCCURRENCE_NOT_FOUND"));
    }

    private AutomationTriggerPersistence.Occurrence transition(
            AutomationTriggerPersistence.Occurrence value,
            AutomationTriggerPersistence.OccurrenceState state, Instant now) {
        var updated = new AutomationTriggerPersistence.Occurrence(
                value.id(), value.triggerVersionId(), value.triggerLineageId(), value.subscriptionId(),
                value.tenantId(), value.ownerId(), value.sourceEventSha256(), value.payloadSha256(),
                state, value.occurredAt(), value.admittedAt(), value.revision() + 1,
                value.createdAt(), now);
        return repository.updateOccurrence(updated, value.revision()).orElseThrow(this::conflict);
    }

    private static AutomationTriggerPersistence.Delivery delivery(
            AutomationTriggerPersistence.Delivery value,
            AutomationTriggerPersistence.DeliveryState state, Instant retryAt,
            String claimToken, long fencingToken, String safeCode, String evidence, Instant now) {
        return delivery(value, state, retryAt, claimToken, fencingToken, safeCode, evidence, now, null);
    }

    private static AutomationTriggerPersistence.Delivery delivery(
            AutomationTriggerPersistence.Delivery value,
            AutomationTriggerPersistence.DeliveryState state, Instant retryAt,
            String claimToken, long fencingToken, String safeCode, String evidence,
            Instant now, Instant leaseUntil) {
        return new AutomationTriggerPersistence.Delivery(
                value.id(), value.occurrenceId(), value.tenantId(), value.ownerId(), value.attempt(), state,
                retryAt, claimToken, claimToken == null ? null : "runtime-continuation", leaseUntil,
                fencingToken, safeCode, evidence, value.revision() + 1, value.createdAt(), now);
    }

    private static DispatchView view(
            AutomationTriggerPersistence.Occurrence occurrence,
            AutomationTriggerPersistence.Delivery delivery, String status,
            Instant retryAt, String safeCode) {
        return new DispatchView(occurrence.id(), delivery == null ? null : delivery.id(), status,
                null, null, retryAt, safeCode);
    }

    private static boolean terminal(AutomationTriggerPersistence.OccurrenceState state) {
        return state == AutomationTriggerPersistence.OccurrenceState.SUCCEEDED
                || state == AutomationTriggerPersistence.OccurrenceState.DEAD_LETTERED;
    }

    private BusinessException conflict() {
        return new BusinessException("Automation event execution revision conflict",
                HttpStatus.CONFLICT, "AUTOMATION_EVENT_REVISION_CONFLICT");
    }

    private static void requireStable(String expected, String actual, String code) {
        if (!expected.equals(actual)) throw new BusinessException(
                "Automation stable identity conflict", HttpStatus.CONFLICT, code);
    }

    private static String stableId(String kind, String occurrenceId) {
        return UUID.nameUUIDFromBytes(("automation:" + kind + ":" + occurrenceId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String abbreviate(String value, int maximum) {
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
