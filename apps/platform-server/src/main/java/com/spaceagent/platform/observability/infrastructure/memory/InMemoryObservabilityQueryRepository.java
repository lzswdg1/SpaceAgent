package com.spaceagent.platform.observability.infrastructure.memory;

import com.spaceagent.platform.observability.domain.ObservabilityOverview;
import com.spaceagent.platform.observability.domain.ObservabilityQueryRepository;
import com.spaceagent.platform.observability.domain.ObservabilityRealtime;
import com.spaceagent.platform.observability.domain.ObservabilitySession;
import com.spaceagent.platform.observability.domain.ObservabilitySessionPage;
import com.spaceagent.platform.observability.domain.ObservabilityTimeseriesPoint;
import com.spaceagent.platform.observability.domain.ObservabilityUsageRow;
import com.spaceagent.platform.observability.domain.UsageDimension;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryObservabilityQueryRepository implements ObservabilityQueryRepository {

    @Override
    public ObservabilityOverview overview(String tenantId, Instant since, String bucketSize) {
        return new ObservabilityOverview(
                1, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, null, 0, false);
    }

    @Override
    public List<ObservabilityTimeseriesPoint> timeseries(
            String tenantId, Instant since, String bucketSize) {
        return List.of();
    }

    @Override
    public List<ObservabilityUsageRow> usage(
            String tenantId, Instant since, UsageDimension dimension, int limit) {
        return List.of();
    }

    @Override
    public ObservabilityRealtime realtime(String tenantId) {
        return new ObservabilityRealtime(0, 0, tenantId, tenantId);
    }

    @Override
    public ObservabilitySessionPage sessions(
            String tenantId, Instant since, String status, int offset, int limit) {
        return new ObservabilitySessionPage(List.of(), 0, 0);
    }

    @Override
    public Optional<ObservabilitySession> findSession(String tenantId, String agentRunId) {
        return Optional.empty();
    }
}
