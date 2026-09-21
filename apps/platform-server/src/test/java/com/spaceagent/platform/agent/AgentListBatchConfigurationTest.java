package com.spaceagent.platform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.application.AgentApplicationService;
import com.spaceagent.platform.agent.application.AgentConfigurationFactory;
import com.spaceagent.platform.agent.domain.AgentCurrentConfiguration;
import com.spaceagent.platform.agent.domain.AgentCurrentConfigurationRepository;
import com.spaceagent.platform.agent.domain.AgentDefinition;
import com.spaceagent.platform.agent.domain.AgentDefinitionStatus;
import com.spaceagent.platform.agent.domain.AgentRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentListBatchConfigurationTest {
    @Test
    void tenantListingLoadsAllCurrentConfigurationsInOneBatch() {
        AgentRepository agents = mock(AgentRepository.class);
        AgentCurrentConfigurationRepository configurations =
                mock(AgentCurrentConfigurationRepository.class);
        var one = definition("agent-1", "owner-1");
        var two = definition("agent-2", "owner-2");
        when(agents.findByTenantId("tenant")).thenReturn(List.of(one, two));
        when(configurations.findByTenantAndAgentIds(
                "tenant", List.of("agent-1", "agent-2")))
                .thenReturn(List.of(configuration(one), configuration(two)));
        var service = new AgentApplicationService(
                agents, () -> "id", () -> Instant.EPOCH,
                null, null, null, new AgentConfigurationFactory(new ObjectMapper()),
                null, configurations);

        assertThat(service.listByTenant("tenant"))
                .extracting(value -> value.id())
                .containsExactly("agent-1", "agent-2");
        verify(configurations).findByTenantAndAgentIds(
                "tenant", List.of("agent-1", "agent-2"));
        verify(configurations, never()).find(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    private static AgentDefinition definition(String id, String owner) {
        return new AgentDefinition(
                id, owner, "tenant", id, null, AgentDefinitionStatus.ACTIVE,
                1, Instant.EPOCH, Instant.EPOCH, null);
    }

    private static AgentCurrentConfiguration configuration(AgentDefinition definition) {
        return new AgentCurrentConfiguration(
                definition.id(), definition.ownerId(), definition.tenantId(), 1,
                "a".repeat(64), null, null, null, "", 0.7, 200_000, 4_096, 25,
                true, false, false, List.of(), List.of(), List.of(), "private",
                List.of(), definition.ownerId(), Instant.EPOCH);
    }
}
