package com.spaceagent.platform.observability.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Read-only CQRS projection; never a Runtime recovery source. A null since means all retained history. */
public interface ObservabilityQueryRepository {

    ObservabilityOverview overview(String tenantId, Instant since, String bucketSize);

    List<ObservabilityTimeseriesPoint> timeseries(
            String tenantId, Instant since, String bucketSize);

    List<ObservabilityUsageRow> usage(
            String tenantId, Instant since, UsageDimension dimension, int limit);

    ObservabilityRealtime realtime(String tenantId);

    ObservabilitySessionPage sessions(
            String tenantId,
            Instant since,
            String status,
            int offset,
            int limit);

    Optional<ObservabilitySession> findSession(String tenantId, String agentRunId);
}
