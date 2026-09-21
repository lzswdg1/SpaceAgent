package com.spaceagent.platform.runtime.domain;

import java.time.Instant;
import java.util.Objects;

/** PostgreSQL-authoritative right for one worker to advance an AgentRun. */
public record RunWorkerLease(
        String agentRunId,
        String leaseToken,
        String leaseOwner,
        long fencingToken,
        Instant leaseUntil,
        long revision,
        Instant acquiredAt,
        Instant heartbeatAt,
        Instant releasedAt) {

    public RunWorkerLease {
        require(agentRunId, "agentRunId");
        require(leaseToken, "leaseToken");
        require(leaseOwner, "leaseOwner");
        if (fencingToken < 1 || revision < 1) {
            throw new IllegalArgumentException("lease fencingToken and revision must be positive");
        }
        Objects.requireNonNull(leaseUntil, "leaseUntil");
        Objects.requireNonNull(acquiredAt, "acquiredAt");
        Objects.requireNonNull(heartbeatAt, "heartbeatAt");
    }

    public boolean activeAt(Instant instant) {
        return releasedAt == null && leaseUntil.isAfter(instant);
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
