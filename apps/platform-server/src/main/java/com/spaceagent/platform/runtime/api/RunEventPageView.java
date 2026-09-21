package com.spaceagent.platform.runtime.api;

import java.util.List;

public record RunEventPageView(
        String agentRunId,
        long afterSequence,
        long nextSequence,
        boolean terminal,
        List<RunEventView> events) {

    public RunEventPageView {
        events = List.copyOf(events);
    }
}
