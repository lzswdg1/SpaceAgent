package com.spaceagent.platform.observability.domain;

import java.util.List;

public record ObservabilitySessionPage(
        List<ObservabilitySession> sessions,
        long total,
        long runtimeActive) {

    public ObservabilitySessionPage {
        sessions = List.copyOf(sessions);
    }
}
