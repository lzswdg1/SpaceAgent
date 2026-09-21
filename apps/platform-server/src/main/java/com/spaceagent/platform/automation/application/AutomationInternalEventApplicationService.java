package com.spaceagent.platform.automation.application;

import com.spaceagent.platform.automation.api.AutomationInternalEventApplicationApi;
import com.spaceagent.platform.automation.domain.AutomationTrigger;
import com.spaceagent.platform.automation.domain.AutomationTriggerPersistence;
import com.spaceagent.platform.automation.domain.AutomationTriggerRepository;
import com.spaceagent.platform.automation.domain.AutomationTriggerSource;
import com.spaceagent.platform.automation.domain.AutomationTriggerState;
import com.spaceagent.platform.automation.domain.AutomationTriggerType;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AutomationInternalEventApplicationService
        implements AutomationInternalEventApplicationApi {
    private final AutomationTriggerRepository repository;
    private final IdGenerator ids;
    private final TimeProvider time;

    public AutomationInternalEventApplicationService(
            AutomationTriggerRepository repository, IdGenerator ids, TimeProvider time) {
        this.repository = repository;
        this.ids = ids;
        this.time = time;
    }

    @Override
    @Transactional(readOnly = true)
    public InternalPolicy prepare(String triggerVersionId, String subscriptionId) {
        var subscription = repository.findSubscriptionById(subscriptionId)
                .orElseThrow(this::unavailable);
        if (!subscription.triggerVersionId().equals(triggerVersionId)
                || subscription.state() != AutomationTriggerPersistence.SubscriptionState.ACTIVE
                || (subscription.triggerType() != AutomationTriggerType.TASK_COMPLETION
                    && subscription.triggerType() != AutomationTriggerType.FOLLOW_UP)) {
            throw unavailable();
        }
        AutomationTrigger trigger = repository.findTrigger(
                        subscription.tenantId(), subscription.ownerId(), triggerVersionId)
                .orElseThrow(this::unavailable);
        if (trigger.state() != AutomationTriggerState.ACTIVE) throw unavailable();
        if (trigger.source() instanceof AutomationTriggerSource.TaskCompletion source) {
            return new InternalPolicy(trigger.id(), trigger.lineageId(), subscription.id(),
                    trigger.tenantId(), trigger.ownerId(), trigger.type(), source.projectId(),
                    source.taskId(), source.outcomes(), null, null, null);
        }
        if (trigger.source() instanceof AutomationTriggerSource.FollowUp source) {
            return new InternalPolicy(trigger.id(), trigger.lineageId(), subscription.id(),
                    trigger.tenantId(), trigger.ownerId(), trigger.type(), null, null, null,
                    source.conversationId(), source.sourceTaskId(), source.delaySeconds());
        }
        throw unavailable();
    }

    @Override
    public AdmissionResult admit(VerifiedInternalEvent event) {
        InternalPolicy current = prepare(
                event.policy().triggerVersionId(), event.policy().subscriptionId());
        if (!current.equals(event.policy())) throw unavailable();
        var now = time.now();
        var occurrence = new AutomationTriggerPersistence.Occurrence(
                ids.nextId(), current.triggerVersionId(), current.triggerLineageId(),
                current.subscriptionId(), current.tenantId(), current.ownerId(),
                event.sourceEventSha256(), event.evidenceSha256(),
                AutomationTriggerPersistence.OccurrenceState.READY,
                event.occurredAt(), now, 1, now, now);
        return new AdmissionResult(repository.createOrFindOccurrence(occurrence).id());
    }

    private BusinessException unavailable() {
        return new BusinessException("Internal Automation event is unavailable", HttpStatus.NOT_FOUND,
                "AUTOMATION_INTERNAL_EVENT_UNAVAILABLE");
    }
}
