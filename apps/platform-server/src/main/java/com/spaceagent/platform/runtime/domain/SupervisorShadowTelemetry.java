package com.spaceagent.platform.runtime.domain;

/** Records bounded shadow-comparison evidence without granting execution authority. */
public interface SupervisorShadowTelemetry {

    void record(SupervisorPolicyMode mode, SupervisorShadowComparison comparison);

    static SupervisorShadowTelemetry noop() {
        return (mode, comparison) -> { };
    }
}
