package com.spaceagent.platform.observability.domain;

import java.util.Locale;

public enum TraceSpanType {
    ROOT,
    LLM,
    TOOL,
    SYSTEM,
    ERROR;

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
