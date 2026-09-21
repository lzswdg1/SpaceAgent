package com.spaceagent.platform.tooling.domain;

/**
 * Explicit outcome of atomically inspecting or claiming one logical tool call.
 */
public enum ToolExecutionClaimDecisionType {
    CLAIMED,
    REPLAY,
    BUSY,
    UNKNOWN,
    CONFLICT
}
