package com.spaceagent.platform.agent.domain;

/** A temporary cross-owner proposal lifecycle; it is not Agent configuration history. */
public enum AgentConfigurationChangeState {
    PENDING,
    APPLIED,
    REJECTED,
    STALE,
    EXPIRED,
    SUPERSEDED;

    public boolean terminal() {
        return this != PENDING;
    }
}
