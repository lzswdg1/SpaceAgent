package com.spaceagent.platform.automation.api;

import com.spaceagent.platform.automation.domain.AutomationTriggerSource;
import com.spaceagent.platform.automation.domain.AutomationTriggerType;
import java.time.Instant;
import java.util.Set;

public interface AutomationInternalEventApplicationApi {
    InternalPolicy prepare(String triggerVersionId, String subscriptionId);

    AdmissionResult admit(VerifiedInternalEvent event);

    record InternalPolicy(
            String triggerVersionId,
            String triggerLineageId,
            String subscriptionId,
            String tenantId,
            String ownerId,
            AutomationTriggerType type,
            String projectId,
            String taskId,
            Set<AutomationTriggerSource.TaskOutcome> outcomes,
            String conversationId,
            String sourceTaskId,
            Integer delaySeconds) {
        public InternalPolicy {
            outcomes = outcomes == null ? Set.of() : Set.copyOf(outcomes);
        }
    }

    record VerifiedInternalEvent(
            InternalPolicy policy,
            String sourceEventSha256,
            String evidenceSha256,
            Instant occurredAt) {
    }

    record AdmissionResult(String occurrenceId) {
    }
}
