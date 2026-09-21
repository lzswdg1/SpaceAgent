package com.spaceagent.platform.tooling.api;

import com.spaceagent.platform.tooling.domain.McpConnectionObservationOutcome;
import com.spaceagent.platform.tooling.domain.McpConnectionState;

import java.time.Instant;
import java.util.List;

public interface McpConnectionQualificationApplicationApi {
    QualificationView qualify(QualifyCommand command);

    QualificationView qualification(String tenantId, String userId, String connectionId);

    List<ObservationView> observations(
            String tenantId, String userId, String connectionId, int limit);

    record QualifyCommand(String tenantId, String userId, String connectionId) {
    }

    record QualificationView(
            String connectionId,
            McpConnectionState state,
            long connectionRevision,
            String snapshotId,
            String snapshotSha256,
            String protocolVersion,
            String serverName,
            String serverTitle,
            String serverVersion,
            int toolCount,
            Instant qualifiedAt,
            ObservationView lastObservation) {
    }

    record ObservationView(
            String id,
            McpConnectionObservationOutcome outcome,
            long connectionRevision,
            long latencyMs,
            String protocolVersion,
            String snapshotId,
            String safeErrorCode,
            Instant observedAt) {
    }
}
