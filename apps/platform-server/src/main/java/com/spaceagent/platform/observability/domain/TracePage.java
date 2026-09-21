package com.spaceagent.platform.observability.domain;

import java.util.List;

public record TracePage(List<TraceSummary> traces, long total, int limit, int offset) {
    public TracePage {
        traces = List.copyOf(traces);
    }
}
