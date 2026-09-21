package com.spaceagent.platform.tooling.domain;

/** Integration records redacted, original-request-correlated observations through Runtime owner APIs. */
public interface SandboxResourceObservationPort {
    void record(SandboxExecutionRequest request, SandboxResourceMetrics metrics);
}
