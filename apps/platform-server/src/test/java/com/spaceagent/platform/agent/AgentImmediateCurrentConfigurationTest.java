package com.spaceagent.platform.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.api.CreateAgentDefinitionCommand;
import com.spaceagent.platform.agent.api.UpdateAgentDefinitionCommand;
import com.spaceagent.platform.agent.application.AgentApplicationService;
import com.spaceagent.platform.agent.application.AgentConfigurationFactory;
import com.spaceagent.platform.agent.infrastructure.memory.InMemoryAgentCurrentConfigurationRepository;
import com.spaceagent.platform.agent.infrastructure.memory.InMemoryAgentRepository;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AgentImmediateCurrentConfigurationTest {

    @Test
    void createAndSaveImmediatelyReplaceTheEffectiveConfiguration() {
        AtomicInteger sequence = new AtomicInteger();
        IdGenerator ids = () -> "id-" + sequence.incrementAndGet();
        TimeProvider time = () -> Instant.parse("2026-09-11T09:00:00Z");
        var definitions = new InMemoryAgentRepository();
        var current = new InMemoryAgentCurrentConfigurationRepository();
        var configurations = new AgentConfigurationFactory(new ObjectMapper());
        var service = new AgentApplicationService(
                definitions, ids, time, null, null, null, configurations, null, current);

        var created = service.create(new CreateAgentDefinitionCommand(
                "owner-1", "tenant-1", "Agent", "description", "prompt one",
                null, "provider-1", "model-1", 0.2, 4096, 25, "ask",
                true, false, true, List.of(), List.of("web_search"), List.of()));

        assertThat(created.revision()).isEqualTo(1);
        assertThat(current.find("tenant-1", "owner-1", created.id()))
                .get().extracting(value -> value.systemPrompt())
                .isEqualTo("prompt one");

        var updated = service.update(new UpdateAgentDefinitionCommand(
                "owner-1", "tenant-1", created.id(), null, null, "prompt two",
                null, null, null, null, null, null, null, null, null,
                null, null, null, null));

        assertThat(updated.revision()).isEqualTo(2);
        assertThat(current.find("tenant-1", "owner-1", created.id()))
                .get().satisfies(value -> {
                    assertThat(value.systemPrompt()).isEqualTo("prompt two");
                    assertThat(value.agentRevision()).isEqualTo(2);
                });
    }
}
