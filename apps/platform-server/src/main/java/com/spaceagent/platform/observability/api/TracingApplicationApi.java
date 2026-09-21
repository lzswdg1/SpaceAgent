package com.spaceagent.platform.observability.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface TracingApplicationApi {

    TraceListResponse list(TraceQuery query);

    TraceDetailResponse get(TraceByIdQuery query);

    TraceStatsResponse stats(TraceStatsQuery query);

    record TraceQuery(
            String tenantId,
            String userId,
            String status,
            String agentId,
            String sessionId,
            String keyword,
            Long minDurationMs,
            Long maxDurationMs,
            Instant since,
            Instant until,
            int limit,
            int offset) {}

    record TraceByIdQuery(String tenantId, String userId, String traceId) {}

    record TraceStatsQuery(
            String tenantId,
            String userId,
            String status,
            String agentId,
            String sessionId,
            String keyword,
            Long minDurationMs,
            Long maxDurationMs,
            Instant since,
            Instant until,
            String range) {}

    record TraceSummaryView(
            String id,
            String organizationId,
            String sessionId,
            String sessionName,
            String agentId,
            String agentName,
            String userId,
            String rootSpanId,
            String status,
            boolean isError,
            String errorMessage,
            Instant startTime,
            Instant endTime,
            Long durationMs,
            Long firstTokenMs,
            long llmMs,
            long toolWallMs,
            long toolDurationSumMs,
            int spanCount,
            int llmTurns,
            int toolCalls,
            long inputTokens,
            long outputTokens,
            long totalTokens,
            long cacheCreateTokens,
            long cacheReadTokens,
            Double costUsd,
            boolean costEstimated,
            Map<String, Object> metadata,
            Instant createdAt,
            Instant updatedAt) {}

    record TraceSpanView(
            String id,
            String traceId,
            String parentSpanId,
            String spanType,
            String name,
            String status,
            boolean isError,
            String errorMessage,
            Instant startTime,
            Instant endTime,
            Long durationMs,
            String model,
            Long inputTokens,
            Long outputTokens,
            Long totalTokens,
            Long cacheCreateTokens,
            Long cacheReadTokens,
            String inputPreview,
            String outputPreview,
            Map<String, Object> metadata,
            Instant createdAt,
            Instant updatedAt) {}

    record TraceListResponse(List<TraceSummaryView> traces, long total, int limit, int offset) {
        public TraceListResponse {
            traces = List.copyOf(traces);
        }
    }

    record TraceDetailResponse(TraceSummaryView trace, List<TraceSpanView> spans) {
        public TraceDetailResponse {
            spans = List.copyOf(spans);
        }
    }

    record TraceStatsResponse(
            long totalTraces,
            long successTraces,
            long errorTraces,
            long abortedTraces,
            double avgDurationMs,
            double p50DurationMs,
            double p95DurationMs,
            double avgFirstTokenMs,
            long totalTokens,
            Double totalCostUsd,
            String range) {}
}
