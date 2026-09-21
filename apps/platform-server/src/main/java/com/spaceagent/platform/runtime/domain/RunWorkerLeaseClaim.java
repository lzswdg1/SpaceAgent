package com.spaceagent.platform.runtime.domain;

import java.util.Objects;

public record RunWorkerLeaseClaim(
        RunWorkerLeaseClaimType type,
        RunWorkerLease lease,
        String reason) {

    public RunWorkerLeaseClaim {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(lease, "lease");
    }
}
