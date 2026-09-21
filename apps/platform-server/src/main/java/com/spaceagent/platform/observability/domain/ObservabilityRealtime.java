package com.spaceagent.platform.observability.domain;

public record ObservabilityRealtime(
        long runtimeActive,
        long processing,
        String organizationId,
        String organizationName) {
}
