package com.spaceagent.platform.inference.domain;

/** Executable deterministic routing strategies. */
public enum ModelPoolRoutingStrategy {
    PRIORITY,
    WEIGHTED,
    COST,
    LATENCY
}
