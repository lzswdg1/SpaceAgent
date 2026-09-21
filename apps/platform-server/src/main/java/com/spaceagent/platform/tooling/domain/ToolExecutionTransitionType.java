package com.spaceagent.platform.tooling.domain;

/**
 * Result of a fenced ledger transition.
 */
public enum ToolExecutionTransitionType {
    APPLIED,
    CLAIM_LOST,
    CURRENT_UNKNOWN,
    CURRENT_TERMINAL,
    CONFLICT
}
