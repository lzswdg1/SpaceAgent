package com.spaceagent.platform.observability.api;

import java.time.Instant;
import java.util.List;

public interface ObservabilityApplicationApi {

    OverviewResponse overview(MonitoringQuery query);

    UsageResponse usage(UsageQuery query);

    RealtimeStatus realtime(ActorQuery query);

    SessionsResponse sessions(SessionsQuery query);

    SessionView stopSession(StopSessionCommand command);

    BatchStopView stopSessions(BatchStopSessionsCommand command);

    record ActorQuery(String tenantId, String userId) {}

    record MonitoringQuery(String tenantId, String userId, String range) {}

    record UsageQuery(String tenantId, String userId, String range, String groupBy) {}

    record SessionsQuery(
            String tenantId,
            String userId,
            String range,
            String status,
            int page,
            int pageSize) {}

    record StopSessionCommand(String tenantId, String userId, String agentRunId) {}

    record BatchStopSessionsCommand(
            String tenantId,
            String userId,
            List<String> agentRunIds) {

        public BatchStopSessionsCommand {
            agentRunIds = List.copyOf(agentRunIds);
        }
    }

    record MonitoringOverview(
            long totalOrganizations,
            long totalAgents,
            long totalSessions,
            long activeSessions,
            long idleSessions,
            long closedSessions,
            long runtimeActiveSessions,
            long totalInputTokens,
            long totalOutputTokens,
            long totalCacheReadTokens,
            long totalCacheCreateTokens,
            Double totalCostUsd,
            long peakConcurrent,
            boolean hasIncompleteCost,
            String range,
            String bucketSize) {}

    record UsageTimeseriesPoint(
            Instant bucket,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheCreateTokens,
            Double costUsd,
            long calls,
            long activeSessions) {}

    record OverviewResponse(
            MonitoringOverview overview,
            List<UsageTimeseriesPoint> timeseries) {

        public OverviewResponse {
            timeseries = List.copyOf(timeseries);
        }
    }

    record UsageRow(
            String key,
            String name,
            String subLabel,
            Long agentCount,
            long sessionCount,
            long inputTokens,
            long outputTokens,
            long cacheReadTokens,
            long cacheCreateTokens,
            long totalTokens,
            Double costUsd,
            long callCount,
            double avgLatencyMs,
            boolean hasIncompleteCost) {}

    record UsageResponse(
            List<UsageRow> rows,
            Double totalCostUsd,
            boolean truncated,
            String groupBy,
            String range) {

        public UsageResponse {
            rows = List.copyOf(rows);
        }
    }

    record PerOrganizationActive(
            String organizationId,
            String organizationName,
            long count) {}

    record RealtimeStatus(
            long runtimeActive,
            long processing,
            List<PerOrganizationActive> perOrgActive,
            Instant ts) {

        public RealtimeStatus {
            perOrgActive = List.copyOf(perOrgActive);
        }
    }

    record SessionEnvEntry(String key, String value, boolean masked) {}

    record SessionView(
            String id,
            String name,
            String agentId,
            String agentName,
            String organizationId,
            String organizationName,
            String providerName,
            boolean isRuntimeActive,
            long totalInputTokens,
            long totalOutputTokens,
            String status,
            long durationSeconds,
            Instant lastActivityAt,
            long messageCount,
            boolean hasConsumer,
            String source,
            String closeReason,
            boolean isProcessing,
            Instant createdAt,
            Instant updatedAt,
            Instant closedAt,
            List<SessionEnvEntry> envOverridesSanitized) {}

    record SessionsResponse(
            List<SessionView> sessions,
            long total,
            int page,
            int pageSize,
            long runtimeActive) {

        public SessionsResponse {
            sessions = List.copyOf(sessions);
        }
    }

    record BatchStopView(int requested, int stopped, List<String> failedIds) {

        public BatchStopView {
            failedIds = List.copyOf(failedIds);
        }
    }
}
