package com.spaceagent.platform.observability.domain;

import java.util.Optional;

public interface TracingQueryRepository {

    TracePage search(TraceFilter filter, int limit, int offset);

    Optional<TraceDetail> find(String tenantId, String ownerId, String traceId);

    TraceStats stats(TraceFilter filter);
}
