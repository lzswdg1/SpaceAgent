package com.spaceagent.platform.automation.domain;

import java.time.Instant;
import java.util.Objects;

/** Durable Organization-owned schedule. PostgreSQL nextFireAt is scheduling truth. */
public record AutomationSchedule(
        String id,
        String tenantId,
        String ownerId,
        String agentId,
        String description,
        String prompt,
        AutomationScheduleType type,
        String cronExpression,
        Instant scheduledAt,
        String timezone,
        AutomationScheduleState state,
        Instant nextFireAt,
        Instant lastRunAt,
        String lastRunStatus,
        String lastError,
        long runCount,
        int maxRetries,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt) {

    public AutomationSchedule {
        require(id, "id");
        require(tenantId, "tenantId");
        require(ownerId, "ownerId");
        require(agentId, "agentId");
        require(description, "description");
        require(prompt, "prompt");
        require(timezone, "timezone");
        type = Objects.requireNonNull(type, "type");
        state = Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (description.length() > 200 || prompt.length() > 32_000
                || timezone.length() > 80 || (cronExpression != null
                && cronExpression.length() > 120)) {
            throw new IllegalArgumentException("Automation schedule fields exceed limits");
        }
        if ((type == AutomationScheduleType.PERIODIC)
                != (cronExpression != null && !cronExpression.isBlank())) {
            throw new IllegalArgumentException("Periodic schedule requires only cronExpression");
        }
        if ((type == AutomationScheduleType.ONE_TIME) != (scheduledAt != null)) {
            throw new IllegalArgumentException("One-time schedule requires only scheduledAt");
        }
        if (state == AutomationScheduleState.ACTIVE && nextFireAt == null) {
            throw new IllegalArgumentException("Active schedule requires nextFireAt");
        }
        if (runCount < 0 || maxRetries < 0 || maxRetries > 10 || revision < 0) {
            throw new IllegalArgumentException("Invalid schedule counters/revision");
        }
        if ((state == AutomationScheduleState.ARCHIVED) != (archivedAt != null)) {
            throw new IllegalArgumentException("archivedAt must match ARCHIVED state");
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
