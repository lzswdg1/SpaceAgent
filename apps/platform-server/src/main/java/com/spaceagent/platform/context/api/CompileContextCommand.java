package com.spaceagent.platform.context.api;

import com.spaceagent.platform.context.domain.ContextSourceType;

import java.util.List;
import java.util.Objects;

/**
 * Public command for compiling a context package.
 */
public record CompileContextCommand(
        String userId,
        String projectId,
        String taskId,
        String conversationId,
        String agentRunId,
        int tokenBudget,
        String compiledBy,
        List<ContextContribution> contributions) {

    public CompileContextCommand {
        requireNonBlank(userId, "userId");
        requireNonBlank(compiledBy, "compiledBy");
        if (tokenBudget <= 0) {
            throw new IllegalArgumentException("tokenBudget must be positive");
        }
        contributions = contributions == null ? List.of() : List.copyOf(contributions);
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    public record ContextContribution(
            ContextSourceType type,
            String sourceId,
            String content,
            int priority,
            double relevanceScore,
            String dedupeKey) {

        public ContextContribution {
            Objects.requireNonNull(type, "type");
            requireNonBlank(content, "content");
            sourceId = sourceId == null || sourceId.isBlank() ? type.name() : sourceId;
            dedupeKey = dedupeKey == null || dedupeKey.isBlank()
                    ? type.name() + ":" + sourceId + ":" + content
                    : dedupeKey;
        }

        private static void requireNonBlank(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
        }
    }
}
