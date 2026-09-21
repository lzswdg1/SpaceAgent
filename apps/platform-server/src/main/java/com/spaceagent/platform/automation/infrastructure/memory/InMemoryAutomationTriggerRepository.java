package com.spaceagent.platform.automation.infrastructure.memory;

import com.spaceagent.platform.automation.domain.AutomationTrigger;
import com.spaceagent.platform.automation.domain.AutomationTriggerPersistence;
import com.spaceagent.platform.automation.domain.AutomationTriggerRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@ConditionalOnProperty(
        prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryAutomationTriggerRepository implements AutomationTriggerRepository {
    private final Map<String, AutomationTrigger> triggers = new HashMap<>();
    private final Map<String, AutomationTriggerPersistence.Subscription> subscriptions = new HashMap<>();
    private final Map<String, AutomationTriggerPersistence.Occurrence> occurrences = new HashMap<>();
    private final Map<String, AutomationTriggerPersistence.Delivery> deliveries = new HashMap<>();
    private final Map<String, AutomationTriggerPersistence.DeadLetter> deadLetters = new HashMap<>();

    @Override
    public synchronized void insertTrigger(AutomationTrigger trigger) {
        if (triggers.putIfAbsent(trigger.id(), trigger) != null
                || triggers.values().stream().anyMatch(value -> !value.id().equals(trigger.id())
                        && value.lineageId().equals(trigger.lineageId())
                        && value.version() == trigger.version())) {
            throw new IllegalStateException("Automation Trigger version already exists");
        }
    }

    @Override
    public synchronized Optional<AutomationTrigger> findTrigger(
            String tenantId, String ownerId, String triggerVersionId) {
        return Optional.ofNullable(triggers.get(triggerVersionId))
                .filter(value -> value.tenantId().equals(tenantId) && value.ownerId().equals(ownerId));
    }

    @Override
    public synchronized List<AutomationTrigger> findTriggerLineage(
            String tenantId, String ownerId, String lineageId) {
        return triggers.values().stream()
                .filter(value -> value.tenantId().equals(tenantId)
                        && value.ownerId().equals(ownerId)
                        && value.lineageId().equals(lineageId))
                .sorted(Comparator.comparingInt(AutomationTrigger::version))
                .toList();
    }

    @Override
    public synchronized Optional<AutomationTrigger> updateTrigger(
            AutomationTrigger trigger, long expectedRevision) {
        AutomationTrigger current = triggers.get(trigger.id());
        if (current == null || current.revision() != expectedRevision
                || trigger.revision() != expectedRevision + 1
                || !current.configSha256().equals(trigger.configSha256())) return Optional.empty();
        triggers.put(trigger.id(), trigger);
        return Optional.of(trigger);
    }

    @Override public synchronized List<AutomationTrigger> findByAgent(String tenant,String owner,String agent){return triggers.values().stream().filter(v->v.tenantId().equals(tenant)&&v.ownerId().equals(owner)&&v.agentId().equals(agent)).sorted(Comparator.comparing(AutomationTrigger::lineageId).thenComparingInt(AutomationTrigger::version)).toList();}

    @Override
    public synchronized void insertSubscription(AutomationTriggerPersistence.Subscription subscription) {
        if (subscriptions.putIfAbsent(subscription.id(), subscription) != null) {
            throw new IllegalStateException("Automation subscription already exists");
        }
    }

    @Override
    public synchronized Optional<AutomationTriggerPersistence.Subscription> findSubscription(
            String tenantId, String ownerId, String subscriptionId) {
        return Optional.ofNullable(subscriptions.get(subscriptionId))
                .filter(value -> value.tenantId().equals(tenantId) && value.ownerId().equals(ownerId));
    }

    @Override
    public synchronized Optional<AutomationTriggerPersistence.Subscription> findSubscriptionById(
            String subscriptionId) {
        return Optional.ofNullable(subscriptions.get(subscriptionId));
    }

    @Override
    public synchronized AutomationTriggerPersistence.Occurrence createOrFindOccurrence(
            AutomationTriggerPersistence.Occurrence occurrence) {
        return occurrences.values().stream()
                .filter(value -> value.triggerLineageId().equals(occurrence.triggerLineageId())
                        && value.sourceEventSha256().equals(occurrence.sourceEventSha256()))
                .findFirst()
                .orElseGet(() -> {
                    occurrences.put(occurrence.id(), occurrence);
                    return occurrence;
                });
    }

    @Override
    public synchronized Optional<AutomationTriggerPersistence.Occurrence> findOccurrence(
            String tenantId, String ownerId, String occurrenceId) {
        return Optional.ofNullable(occurrences.get(occurrenceId))
                .filter(value -> value.tenantId().equals(tenantId) && value.ownerId().equals(ownerId));
    }

    @Override public synchronized Optional<AutomationTriggerPersistence.Subscription> findSubscriptionByTrigger(String trigger){return subscriptions.values().stream().filter(v->v.triggerVersionId().equals(trigger)).findFirst();}

    @Override
    public synchronized Optional<AutomationTriggerPersistence.Occurrence> findOccurrenceById(
            String occurrenceId) {
        return Optional.ofNullable(occurrences.get(occurrenceId));
    }
    @Override public synchronized Optional<AutomationTriggerPersistence.Occurrence> claimNextDispatchable(java.time.Instant staleBefore,java.time.Instant now){return occurrences.values().stream().filter(v->v.state()==AutomationTriggerPersistence.OccurrenceState.READY||v.state()==AutomationTriggerPersistence.OccurrenceState.DISPATCHING&&!v.updatedAt().isAfter(staleBefore)).sorted(Comparator.comparing(AutomationTriggerPersistence.Occurrence::createdAt).thenComparing(AutomationTriggerPersistence.Occurrence::id)).findFirst().map(v->{var claimed=new AutomationTriggerPersistence.Occurrence(v.id(),v.triggerVersionId(),v.triggerLineageId(),v.subscriptionId(),v.tenantId(),v.ownerId(),v.sourceEventSha256(),v.payloadSha256(),AutomationTriggerPersistence.OccurrenceState.DISPATCHING,v.occurredAt(),v.admittedAt(),v.revision()+1,v.createdAt(),now);occurrences.put(v.id(),claimed);return claimed;});}

    @Override
    public synchronized Optional<AutomationTriggerPersistence.Occurrence> updateOccurrence(
            AutomationTriggerPersistence.Occurrence occurrence, long expectedRevision) {
        var current = occurrences.get(occurrence.id());
        if (current == null || current.revision() != expectedRevision
                || occurrence.revision() != expectedRevision + 1) return Optional.empty();
        occurrences.put(occurrence.id(), occurrence);
        return Optional.of(occurrence);
    }

    @Override
    public synchronized void insertDelivery(AutomationTriggerPersistence.Delivery delivery) {
        if (deliveries.putIfAbsent(delivery.id(), delivery) != null) {
            throw new IllegalStateException("Automation delivery already exists");
        }
    }

    @Override
    public synchronized AutomationTriggerPersistence.Delivery createOrFindDelivery(
            AutomationTriggerPersistence.Delivery delivery) {
        return deliveries.values().stream()
                .filter(value -> value.occurrenceId().equals(delivery.occurrenceId())
                        && value.attempt() == delivery.attempt())
                .findFirst().orElseGet(() -> {
                    deliveries.put(delivery.id(), delivery);
                    return delivery;
                });
    }

    @Override
    public synchronized Optional<AutomationTriggerPersistence.Delivery> findDelivery(
            String tenantId, String ownerId, String deliveryId) {
        return Optional.ofNullable(deliveries.get(deliveryId))
                .filter(value -> value.tenantId().equals(tenantId) && value.ownerId().equals(ownerId));
    }

    @Override
    public synchronized Optional<AutomationTriggerPersistence.Delivery> findLatestDelivery(
            String occurrenceId) {
        return deliveries.values().stream()
                .filter(value -> value.occurrenceId().equals(occurrenceId))
                .max(Comparator.comparingInt(AutomationTriggerPersistence.Delivery::attempt));
    }

    @Override
    public synchronized Optional<AutomationTriggerPersistence.Delivery> updateDelivery(
            AutomationTriggerPersistence.Delivery delivery, long expectedRevision) {
        AutomationTriggerPersistence.Delivery current = deliveries.get(delivery.id());
        if (current == null || current.revision() != expectedRevision
                || delivery.revision() != expectedRevision + 1) return Optional.empty();
        deliveries.put(delivery.id(), delivery);
        return Optional.of(delivery);
    }

    @Override
    public synchronized AutomationTriggerPersistence.DeadLetter createOrFindDeadLetter(
            AutomationTriggerPersistence.DeadLetter deadLetter) {
        return deadLetters.values().stream()
                .filter(value -> value.occurrenceId().equals(deadLetter.occurrenceId()))
                .findFirst()
                .orElseGet(() -> {
                    deadLetters.put(deadLetter.id(), deadLetter);
                    return deadLetter;
                });
    }
}
