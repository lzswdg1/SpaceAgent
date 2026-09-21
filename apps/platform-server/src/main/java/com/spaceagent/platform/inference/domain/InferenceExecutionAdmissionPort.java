package com.spaceagent.platform.inference.domain;

/** Admission policy supplied by Integration; Inference owns no Identity or Runtime state. */
public interface InferenceExecutionAdmissionPort {
    void requireDispatch(String tenantId, String agentRunId);

    static InferenceExecutionAdmissionPort unavailable() {
        return (tenant, run) -> { throw new IllegalStateException("Runtime inference admission is not configured"); };
    }
}
