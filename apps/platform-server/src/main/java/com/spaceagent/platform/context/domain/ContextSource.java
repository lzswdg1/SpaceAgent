package com.spaceagent.platform.context.domain;

/**
 * A selected context contribution carrying model-consumable content.
 */
public record ContextSource(
        ContextSourceType type,
        String sourceId,
        String content,
        int tokenCost,
        int priority,
        double relevanceScore) {
}
