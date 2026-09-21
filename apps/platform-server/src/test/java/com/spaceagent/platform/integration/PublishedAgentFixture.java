package com.spaceagent.platform.integration;

import com.spaceagent.platform.agent.domain.AgentCurrentConfigurationRepository;
import com.spaceagent.platform.agent.domain.AgentRepository;
import org.springframework.stereotype.Component;

/** Verifies the immediately usable Agent prerequisite for production HTTP tests. */
@Component
class PublishedAgentFixture {
    private final AgentRepository agents;
    private final AgentCurrentConfigurationRepository configurations;

    PublishedAgentFixture(
            AgentRepository agents, AgentCurrentConfigurationRepository configurations) {
        this.agents = agents;
        this.configurations = configurations;
    }

    void publish(String agentId) {
        var agent = agents.findById(agentId).orElseThrow();
        if (configurations.find(agent.tenantId(), agent.ownerId(), agentId).isEmpty()) {
            throw new IllegalStateException("fixture Agent has no current configuration");
        }
    }
}
