package com.spaceagent.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.governance.api.GovernanceApplicationApi;
import com.spaceagent.platform.knowledge.api.KnowledgeApplicationApi;
import com.spaceagent.platform.knowledge.api.KnowledgeRetrievalMatchView;
import com.spaceagent.platform.knowledge.api.KnowledgeRetrievalView;
import com.spaceagent.platform.project.api.WorkspaceToolApplicationApi;
import com.spaceagent.platform.runtime.api.AgentRunView;
import com.spaceagent.platform.runtime.api.CodingRuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeToolExecutionApplicationApi;
import com.spaceagent.platform.runtime.application.RuntimeToolExecutionApplicationService;
import com.spaceagent.platform.runtime.domain.AgentRunConfigurationSnapshot;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryAgentRunConfigurationSnapshotRepository;
import com.spaceagent.platform.tooling.api.ExternalRuntimeToolApplicationApi;
import com.spaceagent.platform.tooling.api.McpBindingValidationApplicationApi;
import com.spaceagent.platform.tooling.api.SandboxToolExecutionApplicationApi;
import com.spaceagent.platform.tooling.application.RuntimeCapabilityCatalogApplicationService;
import com.spaceagent.platform.tooling.application.ToolExecutionLedgerService;
import com.spaceagent.platform.tooling.domain.WebSearchGateway;
import com.spaceagent.platform.tooling.infrastructure.SandboxProperties;
import com.spaceagent.platform.tooling.infrastructure.memory.InMemoryToolExecutionLedgerRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeToolRunSnapshotTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void authorizesKnowledgeFromTheImmutableRunSnapshotAndReplaysLedgerResult(boolean collection) {
        Instant now = Instant.parse("2026-09-11T00:00:00Z");
        RuntimeApplicationApi runtime = mock(RuntimeApplicationApi.class);
        when(runtime.findRun("run-1")).thenReturn(Optional.of(new AgentRunView(
                "run-1", "agent-1", "run-1", "tenant", "user", "conversation",
                null, null, null, null, null, 0, AgentRunState.WAITING_FOR_TOOL,
                null, now, now, null)));
        KnowledgeApplicationApi knowledge = mock(KnowledgeApplicationApi.class);
        when(knowledge.retrieve(any())).thenReturn(new KnowledgeRetrievalView(
                "runtime", List.of(new KnowledgeRetrievalMatchView(
                "document-1", "chunk-1", 0, "snapshot result", 0.9, "embed"))));
        var snapshots = new InMemoryAgentRunConfigurationSnapshotRepository();
        snapshots.insertIfAbsent(new AgentRunConfigurationSnapshot(
                "run-1", "run-1", "tenant", "user", "agent-1",
                AgentRunConfigurationSnapshot.State.SNAPSHOTTED, 3L, "a".repeat(64),
                null, null, null, "prompt", 0.2, 200_000, 4096, 25,
                true, true, false, collection?List.of():List.of("knowledge-1"),
                List.of("knowledge_search"), List.of(), "ask", List.of(),
                "user", now, now,collection?List.of("base-1"):List.of()));
        AtomicInteger ids = new AtomicInteger();
        var ledger = new ToolExecutionLedgerService(
                new InMemoryToolExecutionLedgerRepository(),
                () -> "ledger-" + ids.incrementAndGet(), () -> now);
        SandboxProperties sandboxProperties = new SandboxProperties();
        sandboxProperties.setMode("http");
        WebSearchGateway search = new WebSearchGateway() {
            public boolean available() { return false; }
            public SearchResult search(SearchRequest request) {
                throw new IllegalStateException("disabled");
            }
        };
        RuntimeToolExecutionApplicationApi service = new RuntimeToolExecutionApplicationService(
                runtime, new RuntimeCapabilityCatalogApplicationService(
                        new ObjectMapper(), search, sandboxProperties), ledger,
                mock(SandboxToolExecutionApplicationApi.class),
                mock(ExternalRuntimeToolApplicationApi.class), knowledge,
                mock(WorkspaceToolApplicationApi.class), mock(CodingRuntimeApplicationApi.class),
                mock(GovernanceApplicationApi.class), null, new ObjectMapper(),
                com.spaceagent.platform.runtime.domain.RuntimeOperationalTelemetry.noop(), snapshots,
                mock(McpBindingValidationApplicationApi.class), (t, u, w) -> {});

        var collections=mock(com.spaceagent.platform.knowledge.api.KnowledgeCollectionRetrievalApi.class);
        when(collections.retrieve(any())).thenReturn(new com.spaceagent.platform.knowledge.api.KnowledgeCollectionRetrievalApi.Result(
            List.of(new com.spaceagent.platform.knowledge.api.KnowledgeCollectionRetrievalApi.Hit("snapshot result",.1,
                new com.spaceagent.platform.knowledge.api.KnowledgeCollectionRetrievalApi.Citation("base-1","document-1","title","generation","chunk",1,"a".repeat(64),java.util.Map.of()))),false,false,false,5,List.of()));
        org.springframework.test.util.ReflectionTestUtils.setField(service,"collectionRetrieval",collections);
        var command = new RuntimeToolExecutionApplicationApi.ExecuteRuntimeToolCommand(
                "user", "run-1", "step-1", "call-1", "knowledge_search",
                "{\"query\":\"runtime\",\"topK\":3}");
        var result = service.execute(command);
        var replay = service.execute(command);

        assertThat(result.result()).contains("snapshot result", "document-1");
        assertThat(replay.result()).isEqualTo(result.result());
        verify(knowledge, times(collection?0:1)).retrieve(any());
        verify(collections,times(collection?1:0)).retrieve(org.mockito.ArgumentMatchers.argThat(q->q.actor().userId().equals("user") && q.actor().organizationId().equals("tenant") && q.baseIds().equals(List.of("base-1"))));
    }
}
