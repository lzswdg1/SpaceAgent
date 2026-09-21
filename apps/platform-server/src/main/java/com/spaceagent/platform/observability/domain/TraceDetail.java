package com.spaceagent.platform.observability.domain;

import java.util.List;

public record TraceDetail(TraceSummary trace, List<TraceSpan> spans) {
    public TraceDetail {
        spans = List.copyOf(spans);
    }
}
