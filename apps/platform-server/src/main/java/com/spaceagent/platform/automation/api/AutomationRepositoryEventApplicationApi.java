package com.spaceagent.platform.automation.api;

import com.spaceagent.platform.automation.domain.AutomationTriggerSource;
import java.time.Instant;
import java.util.Set;

public interface AutomationRepositoryEventApplicationApi {
    RepositoryPolicy prepare(String triggerVersionId, String subscriptionId);

    AdmissionResult admit(VerifiedRepositoryEvent event);

    record RepositoryPolicy(
            String triggerVersionId,
            String triggerLineageId,
            String subscriptionId,
            String tenantId,
            String ownerId,
            String installationId,
            String connectionId,
            long connectionRevision,
            String capabilitySnapshotId,
            String snapshotSha256,
            String providerRepositoryId,
            Set<AutomationTriggerSource.RepositoryEvent> events) {
        public RepositoryPolicy {
            events = Set.copyOf(events);
        }
    }

    record VerifiedRepositoryEvent(
            RepositoryPolicy policy,
            AutomationTriggerSource.RepositoryEvent event,
            String sourceEventSha256,
            String payloadSha256,
            Instant occurredAt) {
    }

    record AdmissionResult(String occurrenceId) {
    }
}
