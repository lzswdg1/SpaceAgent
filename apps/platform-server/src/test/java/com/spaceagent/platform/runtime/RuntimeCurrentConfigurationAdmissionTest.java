package com.spaceagent.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.agent.api.AgentCurrentConfigurationApplicationApi;
import com.spaceagent.platform.runtime.api.StartAgentRunCommand;
import com.spaceagent.platform.runtime.application.RuntimeApplicationService;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryAgentRunConfigurationSnapshotRepository;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryRuntimeLedgerRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeCurrentConfigurationAdmissionTest {

    @Test
    void runCanStartWithoutAgentVersionAndPinsCurrentConfigurationOnce() {
        Instant now = Instant.parse("2026-09-11T09:30:00Z");
        var current = mock(AgentCurrentConfigurationApplicationApi.class);
        when(current.requireCurrent("tenant-1", "owner-1", "agent-1"))
                .thenReturn(configuration("a", "prompt one", now));
        var snapshots = new InMemoryAgentRunConfigurationSnapshotRepository();
        var runtime = new RuntimeApplicationService(
                new InMemoryRuntimeLedgerRepository(), null, new ObjectMapper(),
                () -> "run-1", () -> now, null, null, null, current, snapshots);

        var run = runtime.startRun(new StartAgentRunCommand(
                "tenant-1", "owner-1", "agent-1", null, "conversation-1",
                null, null, null, null, null, null, null));

        assertThat(run.configurationSnapshotId()).isEqualTo(run.id());
        assertThat(snapshots.findByRunId("tenant-1", "owner-1", run.id()))
                .get().satisfies(snapshot -> {
                    assertThat(snapshot.configHash()).isEqualTo("a".repeat(64));
                    assertThat(snapshot.systemPrompt()).isEqualTo("prompt one");
                    assertThat(snapshot.knowledgeCollectionIds()).containsExactly("collection-a");
                });

        when(current.requireCurrent("tenant-1", "owner-1", "agent-1"))
                .thenReturn(configuration("b", "prompt two", now.plusSeconds(1)));
        assertThat(snapshots.findByRunId("tenant-1", "owner-1", run.id()))
                .get().extracting(snapshot -> snapshot.systemPrompt())
                .isEqualTo("prompt one");
        assertThat(snapshots.findByRunId("tenant-1", "owner-1", run.id()).orElseThrow().knowledgeCollectionIds())
                .containsExactly("collection-a");
    }

    private static AgentCurrentConfigurationApplicationApi.ConfigurationView configuration(
            String hashCharacter, String prompt, Instant updatedAt) {
        return new AgentCurrentConfigurationApplicationApi.ConfigurationView(
                "agent-1", "owner-1", "tenant-1", 1, hashCharacter.repeat(64),
                null, "provider-1", "model-1", prompt, 0.2,
                200_000, 4096, 25, true, false, true,
                List.of(), List.of("web_search"), List.of(), "ask", List.of(),
                "owner-1", updatedAt, List.of("collection-" + hashCharacter));
    }
}
