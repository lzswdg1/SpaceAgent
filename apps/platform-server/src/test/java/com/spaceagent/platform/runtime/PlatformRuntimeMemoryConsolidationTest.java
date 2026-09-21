package com.spaceagent.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.memory.api.CompleteTaskMemoryConsolidationCommand;
import com.spaceagent.platform.memory.api.MemoryApplicationApi;
import com.spaceagent.platform.runtime.api.CompleteAgentRunCommand;
import com.spaceagent.platform.runtime.api.StartAgentRunCommand;
import com.spaceagent.platform.runtime.application.RuntimeApplicationService;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryRuntimeLedgerRepository;
import com.spaceagent.platform.tooling.infrastructure.memory.InMemoryToolExecutionLedgerRepository;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PlatformRuntimeMemoryConsolidationTest {

    @Test
    void completingTaskScopedRunTriggersTaskMemoryConsolidation() {
        MemoryApplicationApi memoryApi = mock(MemoryApplicationApi.class);
        RuntimeApplicationService runtime = new RuntimeApplicationService(
                new InMemoryRuntimeLedgerRepository(),
                new com.spaceagent.platform.tooling.application.ToolExecutionLedgerService(
                        new InMemoryToolExecutionLedgerRepository(),
                        () -> "ledger-1",
                        () -> java.time.Instant.parse("2026-08-18T00:00:00Z")),
                new ObjectMapper(),
                () -> "run-1",
                () -> java.time.Instant.parse("2026-08-18T00:00:00Z"),
                memoryApi);

        runtime.startRun(new StartAgentRunCommand(
                "user-1", "agent-1", "version-1", "conversation-1", "project-1", "task-1"));
        runtime.markRunInProgress("run-1");
        runtime.complete(new CompleteAgentRunCommand("run-1"));

        verify(memoryApi).consolidateTask(any(CompleteTaskMemoryConsolidationCommand.class));
    }
}
