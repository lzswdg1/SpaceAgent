package com.spaceagent.platform.tooling.domain;

import java.util.Map;

/**
 * Minimal structured evidence used to resolve or explain an ambiguous tool outcome.
 */
public record ToolExecutionReconciliationEvidence(
        String reason,
        String externalExecutionReference,
        Map<String, String> details) {

    public ToolExecutionReconciliationEvidence {
        requireNonBlank(reason, "reason");
        externalExecutionReference = normalize(externalExecutionReference);
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
