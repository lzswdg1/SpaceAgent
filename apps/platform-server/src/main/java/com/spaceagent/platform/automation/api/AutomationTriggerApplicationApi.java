package com.spaceagent.platform.automation.api;

import com.spaceagent.platform.automation.domain.AutomationTriggerSource;
import java.time.Instant;
import java.util.List;

public interface AutomationTriggerApplicationApi {
    TriggerView create(CreateCommand command);
    TriggerView createVersion(CreateVersionCommand command);
    TriggerView activate(LifecycleCommand command);
    TriggerView pause(LifecycleCommand command);
    TriggerView archive(LifecycleCommand command);
    TriggerView get(GetQuery query);
    List<TriggerView> list(ListQuery query);

    record CreateCommand(
            String tenantId,
            String ownerUserId,
            String agentId,
            String description,
            String prompt,
            AutomationTriggerSource source,
            String idempotencyKey) {
    }

    record CreateVersionCommand(
            String tenantId,
            String ownerUserId,
            String triggerLineageId,
            int expectedCurrentVersion,
            String description,
            String prompt,
            AutomationTriggerSource source,
            String idempotencyKey) {
    }

    record LifecycleCommand(
            String tenantId,
            String ownerUserId,
            String triggerVersionId,
            long expectedRevision) {
    }

    record GetQuery(String tenantId, String ownerUserId, String triggerVersionId) {
    }

    record ListQuery(String tenantId, String ownerUserId, String agentId) {
    }

    record TriggerView(
            String id,
            String lineageId,
            int version,
            String previousVersionId,
            String agentId,
            String description,
            String prompt,
            String type,
            String configSha256,
            String state,
            long revision,
            Instant createdAt,
            Instant activatedAt,
            Instant archivedAt) {
    }
}
