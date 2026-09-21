package com.spaceagent.platform.automation.application;

import com.spaceagent.platform.automation.api.AutomationRepositoryEventApplicationApi;
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
public class AutomationRepositoryEventApplicationService
        implements AutomationRepositoryEventApplicationApi {
    private final AutomationTriggerRepository repository;
    private final IdGenerator ids;
    private final TimeProvider time;

    public AutomationRepositoryEventApplicationService(
            AutomationTriggerRepository repository, IdGenerator ids, TimeProvider time) {
        this.repository = repository;
        this.ids = ids;
        this.time = time;
    }

    @Override
    @Transactional(readOnly = true)
    public RepositoryPolicy prepare(String triggerVersionId, String subscriptionId) {
        AutomationTriggerPersistence.Subscription subscription = repository
                .findSubscriptionById(subscriptionId).orElseThrow(this::unavailable);
        if (!subscription.triggerVersionId().equals(triggerVersionId)
                || subscription.triggerType() != AutomationTriggerType.REPOSITORY
                || subscription.state() != AutomationTriggerPersistence.SubscriptionState.ACTIVE) {
            throw unavailable();
        }
        AutomationTrigger trigger = repository.findTrigger(
                        subscription.tenantId(), subscription.ownerId(), triggerVersionId)
                .orElseThrow(this::unavailable);
        if (trigger.state() != AutomationTriggerState.ACTIVE
                || !(trigger.source() instanceof AutomationTriggerSource.Repository source)) {
            throw unavailable();
        }
        return new RepositoryPolicy(trigger.id(), trigger.lineageId(), subscription.id(),
                trigger.tenantId(), trigger.ownerId(), source.installationId(), source.connectionId(),
                source.connectionRevision(), source.capabilitySnapshotId(), source.snapshotSha256(),
                source.providerRepositoryId(), source.events());
    }

    @Override
    public AdmissionResult admit(VerifiedRepositoryEvent event) {
        RepositoryPolicy current = prepare(
                event.policy().triggerVersionId(), event.policy().subscriptionId());
        if (!current.equals(event.policy()) || !current.events().contains(event.event())) {
            throw unavailable();
        }
        var now = time.now();
        var occurrence = new AutomationTriggerPersistence.Occurrence(
                ids.nextId(), current.triggerVersionId(), current.triggerLineageId(),
                current.subscriptionId(), current.tenantId(), current.ownerId(),
                event.sourceEventSha256(), event.payloadSha256(),
                AutomationTriggerPersistence.OccurrenceState.READY,
                event.occurredAt(), now, 1, now, now);
        return new AdmissionResult(repository.createOrFindOccurrence(occurrence).id());
    }

    private BusinessException unavailable() {
        return new BusinessException("Repository event endpoint is unavailable", HttpStatus.NOT_FOUND,
                "AUTOMATION_REPOSITORY_EVENT_UNAVAILABLE");
    }
}
