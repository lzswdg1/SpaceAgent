package com.spaceagent.platform.agent.api;

/** Minimal ownership query for the canonical Agent identity. */
public interface AgentOwnershipPort {
    boolean isOwner(String agentId, String principalId);
}
