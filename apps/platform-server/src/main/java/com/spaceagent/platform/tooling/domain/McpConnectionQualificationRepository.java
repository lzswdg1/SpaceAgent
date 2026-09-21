package com.spaceagent.platform.tooling.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface McpConnectionQualificationRepository {
    Optional<McpCapabilitySnapshot> findCurrentSnapshot(String connectionId);

    List<McpConnectionObservation> findObservations(String connectionId, int limit);

    void completeSuccess(
            String connectionId,
            long expectedRevision,
            McpCapabilitySnapshot snapshot,
            McpConnectionObservation observation,
            Instant at);

    void completeFailure(
            String connectionId,
            long expectedRevision,
            McpConnectionObservation observation,
            Instant at);
}
