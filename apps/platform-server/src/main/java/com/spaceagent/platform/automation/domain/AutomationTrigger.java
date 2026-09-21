package com.spaceagent.platform.automation.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;

/** One immutable configuration version in an Automation Trigger lineage. */
public record AutomationTrigger(
        String id,
        String lineageId,
        int version,
        String previousVersionId,
        String tenantId,
        String ownerId,
        String agentId,
        String description,
        String prompt,
        AutomationTriggerType type,
        AutomationTriggerSource source,
        String configSha256,
        AutomationTriggerState state,
        long revision,
        Instant createdAt,
        Instant activatedAt,
        Instant archivedAt) {

    public AutomationTrigger {
        require(id, "id");
        require(lineageId, "lineageId");
        require(tenantId, "tenantId");
        require(ownerId, "ownerId");
        require(agentId, "agentId");
        require(description, "description");
        require(prompt, "prompt");
        type = Objects.requireNonNull(type, "type");
        source = Objects.requireNonNull(source, "source");
        state = Objects.requireNonNull(state, "state");
        Objects.requireNonNull(createdAt, "createdAt");
        if (version <= 0 || revision <= 0
                || version == 1 && previousVersionId != null
                || version > 1 && (previousVersionId == null || previousVersionId.isBlank())) {
            throw new IllegalArgumentException("Trigger version lineage is invalid");
        }
        if (description.length() > 200 || prompt.length() > 32_000
                || !eventDriven(type) || source.type() != type) {
            throw new IllegalArgumentException("Trigger configuration is invalid");
        }
        String expected = calculateConfigSha256(
                tenantId, ownerId, agentId, description, prompt, type, source);
        if (!expected.equals(configSha256)) {
            throw new IllegalArgumentException("configSha256 does not match Trigger configuration");
        }
        if (state == AutomationTriggerState.DRAFT
                    && (activatedAt != null || archivedAt != null)
                || (state == AutomationTriggerState.ACTIVE
                    || state == AutomationTriggerState.PAUSED)
                    && (activatedAt == null || archivedAt != null)
                || state == AutomationTriggerState.ARCHIVED && archivedAt == null) {
            throw new IllegalArgumentException("Trigger lifecycle timestamps are invalid");
        }
    }

    public AutomationTrigger activate(Instant at) {
        if (state != AutomationTriggerState.DRAFT && state != AutomationTriggerState.PAUSED) {
            throw new IllegalStateException("Only DRAFT or PAUSED Trigger can activate");
        }
        return copy(AutomationTriggerState.ACTIVE, revision + 1,
                activatedAt == null ? at : activatedAt, null);
    }

    public AutomationTrigger pause() {
        if (state != AutomationTriggerState.ACTIVE) {
            throw new IllegalStateException("Only ACTIVE Trigger can pause");
        }
        return copy(AutomationTriggerState.PAUSED, revision + 1, activatedAt, null);
    }

    public AutomationTrigger archive(Instant at) {
        if (state == AutomationTriggerState.ARCHIVED) return this;
        return copy(AutomationTriggerState.ARCHIVED, revision + 1, activatedAt, at);
    }

    public static String calculateConfigSha256(
            String tenantId,
            String ownerId,
            String agentId,
            String description,
            String prompt,
            AutomationTriggerType type,
            AutomationTriggerSource source) {
        return sha256(String.join("\n", tenantId, ownerId, agentId, description,
                prompt, type.name(), source.canonicalValue()));
    }

    private AutomationTrigger copy(
            AutomationTriggerState next,
            long nextRevision,
            Instant nextActivatedAt,
            Instant nextArchivedAt) {
        return new AutomationTrigger(
                id, lineageId, version, previousVersionId, tenantId, ownerId, agentId,
                description, prompt, type, source, configSha256, next, nextRevision,
                createdAt, nextActivatedAt, nextArchivedAt);
    }

    private static boolean eventDriven(AutomationTriggerType type) {
        return type == AutomationTriggerType.WEBHOOK
                || type == AutomationTriggerType.REPOSITORY
                || type == AutomationTriggerType.TASK_COMPLETION
                || type == AutomationTriggerType.FOLLOW_UP;
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
