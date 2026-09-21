package com.spaceagent.platform.runtime.domain;

import java.util.Objects;

public record RuntimeContinuationClaim(
        RuntimeContinuation continuation,
        RunWorkerLease lease) {

    public RuntimeContinuationClaim {
        Objects.requireNonNull(continuation, "continuation");
        Objects.requireNonNull(lease, "lease");
    }
}
