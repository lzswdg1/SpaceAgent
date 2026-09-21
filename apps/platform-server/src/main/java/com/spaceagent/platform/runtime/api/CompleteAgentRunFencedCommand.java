package com.spaceagent.platform.runtime.api;

/** Terminal completion by the worker holding the current Run lease/fence. */
public record CompleteAgentRunFencedCommand(
        String agentRunId, String leaseToken, long fencingToken) {}
