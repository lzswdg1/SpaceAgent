package com.spaceagent.platform.observability.domain;

import java.time.Instant;

/** Reads only disposable observability/trace projections for operational metric export. */
public interface AgentOperationalMetricsQueryRepository {

    AgentOperationalMetricsSnapshot snapshot(Instant since, Instant observedAt);
}
