package com.spaceagent.platform.automation.domain;

import java.util.List;
import java.util.Optional;

public interface AutomationTriggerRepository {
    void insertTrigger(AutomationTrigger trigger);

    Optional<AutomationTrigger> findTrigger(String tenantId, String ownerId, String triggerVersionId);

    List<AutomationTrigger> findTriggerLineage(String tenantId, String ownerId, String lineageId);
    List<AutomationTrigger> findByAgent(String tenantId, String ownerId, String agentId);

    Optional<AutomationTrigger> updateTrigger(AutomationTrigger trigger, long expectedRevision);

    void insertSubscription(AutomationTriggerPersistence.Subscription subscription);

    Optional<AutomationTriggerPersistence.Subscription> findSubscription(
            String tenantId, String ownerId, String subscriptionId);

    Optional<AutomationTriggerPersistence.Subscription> findSubscriptionById(String subscriptionId);
    Optional<AutomationTriggerPersistence.Subscription> findSubscriptionByTrigger(String triggerVersionId);

    AutomationTriggerPersistence.Occurrence createOrFindOccurrence(
            AutomationTriggerPersistence.Occurrence occurrence);

    Optional<AutomationTriggerPersistence.Occurrence> findOccurrence(
            String tenantId, String ownerId, String occurrenceId);

    Optional<AutomationTriggerPersistence.Occurrence> findOccurrenceById(String occurrenceId);
    Optional<AutomationTriggerPersistence.Occurrence> claimNextDispatchable(java.time.Instant staleBefore,java.time.Instant now);

    Optional<AutomationTriggerPersistence.Occurrence> updateOccurrence(
            AutomationTriggerPersistence.Occurrence occurrence, long expectedRevision);

    void insertDelivery(AutomationTriggerPersistence.Delivery delivery);

    AutomationTriggerPersistence.Delivery createOrFindDelivery(
            AutomationTriggerPersistence.Delivery delivery);

    Optional<AutomationTriggerPersistence.Delivery> findDelivery(
            String tenantId, String ownerId, String deliveryId);

    Optional<AutomationTriggerPersistence.Delivery> findLatestDelivery(String occurrenceId);

    Optional<AutomationTriggerPersistence.Delivery> updateDelivery(
            AutomationTriggerPersistence.Delivery delivery, long expectedRevision);

    AutomationTriggerPersistence.DeadLetter createOrFindDeadLetter(
            AutomationTriggerPersistence.DeadLetter deadLetter);
}
