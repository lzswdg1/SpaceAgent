package com.spaceagent.platform.agent.api;

/**
 * One-time API-key creation result. The raw key is never persisted and is only
 * returned from the create call.
 */
public record CreatedAgentApiKeyView(
        String rawKey,
        AgentApiKeyView apiKey) {
}
