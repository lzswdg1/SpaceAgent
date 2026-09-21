package com.spaceagent.platform.agent;

import com.spaceagent.platform.agent.application.AgentCurrentConfigurationApplicationService;
import com.spaceagent.platform.agent.domain.AgentCurrentConfiguration;
import com.spaceagent.platform.agent.domain.AgentDefinition;
import com.spaceagent.platform.agent.domain.AgentDefinitionStatus;
import com.spaceagent.platform.agent.infrastructure.memory.InMemoryAgentCurrentConfigurationRepository;
import com.spaceagent.platform.agent.infrastructure.memory.InMemoryAgentRepository;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentCurrentConfigurationApplicationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");

    @Test
    void resolvesOnlyTheStoredCurrentConfigurationWithinOwnerScope() {
        var agents = new InMemoryAgentRepository();
        var configurations = new InMemoryAgentCurrentConfigurationRepository();
        agents.save(new AgentDefinition("agent", "owner", "tenant", "Agent", null,
                AgentDefinitionStatus.ACTIVE, 1, NOW, NOW, null));
        configurations.insert(configuration());
        var service = new AgentCurrentConfigurationApplicationService(agents, configurations);
        assertThat(service.requireCurrent("tenant", "owner", "agent").modelId())
                .isEqualTo("model");
        assertThat(service.findCurrent("tenant", "other", "agent")).isEmpty();
    }

    @Test
    void refusesAnAgentWithoutCurrentConfiguration() {
        var agents = new InMemoryAgentRepository();
        agents.save(new AgentDefinition("agent", "owner", "tenant", "Agent", null,
                AgentDefinitionStatus.ACTIVE, 1, NOW, NOW, null));
        var service = new AgentCurrentConfigurationApplicationService(
                agents, new InMemoryAgentCurrentConfigurationRepository());
        assertThatThrownBy(() -> service.requireCurrent("tenant", "owner", "agent"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo("AGENT_CURRENT_CONFIGURATION_MISSING"));
    }

    private static AgentCurrentConfiguration configuration() {
        return new AgentCurrentConfiguration(
                "agent", "owner", "tenant", 1, "a".repeat(64), null,
                "provider", "model", "prompt", 0.2, 200000, 4096, 25,
                true, false, true, List.of(), List.of(), List.of(), "ask",
                List.of(), "owner", NOW);
    }
}
