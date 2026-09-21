package com.spaceagent.platform.runtime.api;

/** Terminal failure by the worker holding the current Run lease/fence. */
public record FailAgentRunFencedCommand(
        String agentRunId, String leaseToken, long fencingToken, String reason) {}
