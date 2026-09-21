package com.spaceagent.platform.context.domain;

import java.time.Instant;
import java.util.List;

/**
 * The compiled context package produced by a ContextCompiler and passed to
 * inference/runtime. It is a compiled runtime value, not authoritative durable
 * business state.
 */
public record ContextPackage(
        String id,
        String userId,
        String projectId,
        String taskId,
        String conversationId,
        String agentRunId,
        int tokenBudget,
        int usedTokens,
        List<ContextSource> sources,
        String compiledBy,
        Instant createdAt) {

    public ContextPackage {
        sources = List.copyOf(sources);
    }
}
