package com.spaceagent.platform.governance.domain;

import java.time.Instant;

/** Organization-owned approval policy. A revision of zero is an unpersisted default. */
public record GovernancePolicy(
        String tenantId,
        boolean requireCodingFileApproval,
        boolean requireCommandApproval,
        boolean requireAutomationApproval,
        boolean requireNetworkApproval,
        boolean requireSourceMergeApproval,
        boolean separationOfDuties,
        int approvalTtlSeconds,
        long revision,
        String updatedBy,
        Instant updatedAt) {

    public static final int DEFAULT_TTL_SECONDS = 3_600;
    public static final int MIN_TTL_SECONDS = 60;
    public static final int MAX_TTL_SECONDS = 604_800;

    public GovernancePolicy {
        requireNonBlank(tenantId, "tenantId");
        if (approvalTtlSeconds < MIN_TTL_SECONDS || approvalTtlSeconds > MAX_TTL_SECONDS) {
            throw new IllegalArgumentException("approvalTtlSeconds must be between 60 and 604800");
        }
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        if (revision > 0 && (updatedBy == null || updatedBy.isBlank() || updatedAt == null)) {
            throw new IllegalArgumentException("persisted policy audit fields are required");
        }
    }

    public static GovernancePolicy defaults(String tenantId) {
        return new GovernancePolicy(
                tenantId, false, false, false, false, false,
                false, DEFAULT_TTL_SECONDS, 0, null, null);
    }

    public boolean requiresApproval(GovernanceActionType actionType) {
        return switch (actionType) {
            case CODING_FILE_MUTATION -> requireCodingFileApproval;
            case COMMAND_EXECUTION -> requireCommandApproval;
            case AUTOMATION_TRIGGER -> requireAutomationApproval;
            case NETWORK_ACCESS -> requireNetworkApproval;
            case SOURCE_MERGE -> requireSourceMergeApproval;
            case AGENT_CONFIGURATION_CHANGE -> true;
        };
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
