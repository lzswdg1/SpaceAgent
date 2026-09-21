package com.spaceagent.platform.automation.api;

import java.time.Instant;
import java.util.List;

public interface AutomationWebhookApplicationApi {
    WebhookPolicy prepare(String triggerVersionId, String subscriptionId);

    AdmissionResult admit(VerifiedWebhookEvent event);

    record WebhookPolicy(
            String triggerVersionId,
            String triggerLineageId,
            String subscriptionId,
            String tenantId,
            String ownerId,
            List<String> signingKeyRefs,
            int maxBodyBytes,
            int maxAgeSeconds) {
        public WebhookPolicy {
            signingKeyRefs = List.copyOf(signingKeyRefs);
        }
    }

    record VerifiedWebhookEvent(
            WebhookPolicy policy,
            String sourceEventSha256,
            String payloadSha256,
            Instant occurredAt) {
    }

    record AdmissionResult(String occurrenceId) {
    }
}
