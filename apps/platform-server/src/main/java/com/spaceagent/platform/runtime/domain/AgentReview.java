package com.spaceagent.platform.runtime.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record AgentReview(
        String id,
        String tenantId,
        String parentRunId,
        String childRunId,
        String reviewerAgentId,
        List<String> artifactIds,
        AgentReviewDecision decision,
        String evidence,
        Instant createdAt,
        Instant decidedAt) {

    public AgentReview {
        artifactIds = List.copyOf(artifactIds);
        require(id, "id");
        require(tenantId, "tenantId");
        require(parentRunId, "parentRunId");
        require(childRunId, "childRunId");
        require(reviewerAgentId, "reviewerAgentId");
        if (artifactIds.isEmpty()
                || artifactIds.stream().anyMatch(value -> value == null || value.isBlank())
                || artifactIds.stream().distinct().count() != artifactIds.size()) {
            throw new IllegalArgumentException("artifactIds must be non-empty and unique");
        }
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(createdAt, "createdAt");
        if (decision == AgentReviewDecision.PENDING && (evidence != null || decidedAt != null)) {
            throw new IllegalArgumentException("pending review cannot have decision evidence");
        }
        if (decision != AgentReviewDecision.PENDING
                && (evidence == null || evidence.isBlank() || decidedAt == null)) {
            throw new IllegalArgumentException("decided review requires evidence and decidedAt");
        }
    }

    public AgentReview decide(AgentReviewDecision next, String decisionEvidence, Instant at) {
        return new AgentReview(
                id, tenantId, parentRunId, childRunId, reviewerAgentId, artifactIds,
                next, decisionEvidence, createdAt, at);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
