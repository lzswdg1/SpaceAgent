package com.spaceagent.platform.runtime.api;

import java.math.BigDecimal;
import java.time.Instant;

public interface RuntimeResourceObservationApi {
    Summary summary(String tenantId,String userId,Instant from,Instant to);
    record Summary(long observations,BigDecimal knownCpuUsageNanos,Long maximumObservedMemoryBytes,
            BigDecimal knownNetworkRxBytes,BigDecimal knownNetworkTxBytes,BigDecimal latestObservedWorkspaceApparentBytes,
            long missingCpuObservations,long missingMemoryObservations,long missingNetworkObservations,long missingWorkspaceObservations,
            String coverage,Instant from,Instant to,Instant generatedAt) {}
}
