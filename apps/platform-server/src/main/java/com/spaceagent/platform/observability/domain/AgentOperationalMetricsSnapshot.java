package com.spaceagent.platform.observability.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * Redacted, low-cardinality operational projection for metrics backends.
 *
 * <p>The snapshot is disposable evidence derived from the existing Trace views. It is never a
 * Runtime, billing, approval, or reconciliation source of truth.
 */
public record AgentOperationalMetricsSnapshot(
        Instant observedAt,
        long activeRuns,
        long recoveringRuns,
        OutcomeCounts runs,
        OutcomeCounts modelCalls,
        OutcomeCounts toolExecutions,
        LatencyP95 latencyP95,
        TokenUsage tokens,
        CostEvidence cost,
        CollaborationEvidence collaboration) {

    public AgentOperationalMetricsSnapshot {
        Objects.requireNonNull(observedAt, "observedAt");
        requireNonNegative(activeRuns, "activeRuns");
        requireNonNegative(recoveringRuns, "recoveringRuns");
        Objects.requireNonNull(runs, "runs");
        Objects.requireNonNull(modelCalls, "modelCalls");
        Objects.requireNonNull(toolExecutions, "toolExecutions");
        Objects.requireNonNull(latencyP95, "latencyP95");
        Objects.requireNonNull(tokens, "tokens");
        Objects.requireNonNull(cost, "cost");
        Objects.requireNonNull(collaboration, "collaboration");
    }

    public static AgentOperationalMetricsSnapshot empty(Instant observedAt) {
        OutcomeCounts emptyOutcomes = new OutcomeCounts(0, 0, 0, 0, 0, 0);
        return new AgentOperationalMetricsSnapshot(
                observedAt, 0, 0, emptyOutcomes, emptyOutcomes, emptyOutcomes,
                new LatencyP95(0, 0, 0, 0), new TokenUsage(0, 0, 0, 0),
                new CostEvidence(0, 0),
                new CollaborationEvidence(0, 0, 0, 0, 0, 0));
    }

    public record OutcomeCounts(
            long total,
            long success,
            long error,
            long aborted,
            long unknown,
            long running) {

        public OutcomeCounts {
            requireNonNegative(total, "total");
            requireNonNegative(success, "success");
            requireNonNegative(error, "error");
            requireNonNegative(aborted, "aborted");
            requireNonNegative(unknown, "unknown");
            requireNonNegative(running, "running");
            if (total != success + error + aborted + unknown + running) {
                throw new IllegalArgumentException("Outcome count total must equal its buckets");
            }
        }

        public long value(String outcome) {
            return switch (outcome) {
                case "total" -> total;
                case "success" -> success;
                case "error" -> error;
                case "aborted" -> aborted;
                case "unknown" -> unknown;
                case "running" -> running;
                default -> throw new IllegalArgumentException("Unsupported outcome: " + outcome);
            };
        }
    }

    public record LatencyP95(
            double agentRunMillis,
            double modelCallMillis,
            double toolExecutionMillis,
            double modelFirstChunkMillis) {

        public LatencyP95 {
            requireNonNegative(agentRunMillis, "agentRunMillis");
            requireNonNegative(modelCallMillis, "modelCallMillis");
            requireNonNegative(toolExecutionMillis, "toolExecutionMillis");
            requireNonNegative(modelFirstChunkMillis, "modelFirstChunkMillis");
        }

        public LatencyP95(
                double agentRunMillis, double modelCallMillis, double toolExecutionMillis) {
            this(agentRunMillis, modelCallMillis, toolExecutionMillis, 0);
        }

        public double millis(String operation) {
            return switch (operation) {
                case "agent_run" -> agentRunMillis;
                case "model_call" -> modelCallMillis;
                case "tool_execution" -> toolExecutionMillis;
                case "model_first_chunk" -> modelFirstChunkMillis;
                default -> throw new IllegalArgumentException("Unsupported operation: " + operation);
            };
        }
    }

    public record TokenUsage(
            long input,
            long output,
            long cacheRead,
            long cacheCreate) {

        public TokenUsage {
            requireNonNegative(input, "input");
            requireNonNegative(output, "output");
            requireNonNegative(cacheRead, "cacheRead");
            requireNonNegative(cacheCreate, "cacheCreate");
        }

        public long value(String type) {
            return switch (type) {
                case "input" -> input;
                case "output" -> output;
                case "cache_read" -> cacheRead;
                case "cache_create" -> cacheCreate;
                default -> throw new IllegalArgumentException("Unsupported token type: " + type);
            };
        }
    }

    public record CostEvidence(long settledMicros, long incompleteRuns) {
        public CostEvidence {
            requireNonNegative(settledMicros, "settledMicros");
            requireNonNegative(incompleteRuns, "incompleteRuns");
        }
    }

    public record CollaborationEvidence(
            long handoffs,
            long handoffErrors,
            long reviews,
            long reviewChangesRequested,
            long acceptanceEvidence,
            long continuationFailures) {

        public CollaborationEvidence {
            requireNonNegative(handoffs, "handoffs");
            requireNonNegative(handoffErrors, "handoffErrors");
            requireNonNegative(reviews, "reviews");
            requireNonNegative(reviewChangesRequested, "reviewChangesRequested");
            requireNonNegative(acceptanceEvidence, "acceptanceEvidence");
            requireNonNegative(continuationFailures, "continuationFailures");
        }

        public long value(String kind) {
            return switch (kind) {
                case "handoff" -> handoffs;
                case "handoff_error" -> handoffErrors;
                case "review" -> reviews;
                case "review_changes_requested" -> reviewChangesRequested;
                case "acceptance_evidence" -> acceptanceEvidence;
                case "continuation_failure" -> continuationFailures;
                default -> throw new IllegalArgumentException("Unsupported evidence kind: " + kind);
            };
        }
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }

    private static void requireNonNegative(double value, String field) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(field + " must be finite and non-negative");
        }
    }
}
