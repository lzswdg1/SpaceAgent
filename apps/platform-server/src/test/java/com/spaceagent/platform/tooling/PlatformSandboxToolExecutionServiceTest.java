package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.api.SandboxToolExecutionApplicationApi;
import com.spaceagent.platform.tooling.api.SandboxToolExecutionCommand;
import com.spaceagent.platform.tooling.api.SandboxToolExecutionUnavailableException;
import com.spaceagent.platform.tooling.api.SandboxToolExecutionView;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerView;
import com.spaceagent.platform.tooling.application.SandboxToolExecutionService;
import com.spaceagent.platform.tooling.application.ToolExecutionLedgerService;
import com.spaceagent.platform.tooling.domain.SandboxExecutionGateway;
import com.spaceagent.platform.tooling.domain.SandboxExecutionMetadata;
import com.spaceagent.platform.tooling.domain.SandboxExecutionRequest;
import com.spaceagent.platform.tooling.domain.SandboxExecutionResponse;
import com.spaceagent.platform.tooling.domain.SandboxExecutionStatus;
import com.spaceagent.platform.tooling.domain.SandboxExecutionUnavailableException;
import com.spaceagent.platform.tooling.domain.ToolExecutionStatus;
import com.spaceagent.platform.tooling.infrastructure.memory.InMemoryToolExecutionLedgerRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M7 sandbox-tool boundary tests: ledger wrapping, idempotency, ambiguity, and
 * worker-unavailable behavior.
 */
class PlatformSandboxToolExecutionServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-18T09:00:00Z");

    @Test
    void successfulExecutionWrapsDurableLedger() {
        Harness harness = new Harness(RecordingGateway.succeeding());

        SandboxToolExecutionView result = harness.api.execute(
                command("call-1", "echo", List.of("hello")));

        assertEquals("SUCCEEDED", result.status());
        assertEquals("hello\n", result.result());
        assertEquals(1, harness.gateway.calls());
        assertEquals(List.of("call-1"), harness.ledgerApi.findByRunId("run-1").stream()
                .map(ToolExecutionLedgerView::toolCallId)
                .toList());
    }

    @Test
    void completedToolCallIsReplayedWithoutReexecution() {
        Harness harness = new Harness(RecordingGateway.succeeding());

        harness.api.execute(command("call-1", "echo", List.of("hello")));
        SandboxToolExecutionView replay = harness.api.execute(
                command("call-1", "echo", List.of("hello")));

        assertEquals("SUCCEEDED", replay.status());
        assertEquals(1, harness.gateway.calls());
    }

    @Test
    void unavailableWorkerLeavesUnknownLedgerEntry() {
        Harness harness = new Harness(RecordingGateway.unavailable());

        assertThrows(SandboxToolExecutionUnavailableException.class,
                () -> harness.api.execute(command("call-1", "echo", List.of("hello"))));

        ToolExecutionLedgerView entry = harness.ledgerApi.findByRunId("run-1").get(0);
        assertEquals(ToolExecutionStatus.UNKNOWN, entry.status());
        assertTrue(entry.error().contains("no trustworthy terminal outcome"));
    }

    @Test
    void unknownLedgerEntryIsNotBlindlyReexecuted() {
        Harness harness = new Harness(RecordingGateway.unavailable());

        assertThrows(SandboxToolExecutionUnavailableException.class,
                () -> harness.api.execute(command("call-1", "echo", List.of("hello"))));

        BusinessException error = assertThrows(BusinessException.class,
                () -> harness.api.execute(command("call-1", "echo", List.of("hello"))));
        assertEquals("TOOL_EXECUTION_AMBIGUOUS", error.getCode());
    }

    private SandboxToolExecutionCommand command(
            String toolCallId,
            String command,
            List<String> arguments) {
        return new SandboxToolExecutionCommand(
                "run-1", "step-1", "sandbox-bash", toolCallId, "idem-" + toolCallId,
                command, arguments, "task-1", "task-1", 5);
    }

    private static IdGenerator sequentialIdGenerator(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return () -> prefix + "-" + sequence.incrementAndGet();
    }

    private static TimeProvider fixedTimeProvider() {
        return () -> NOW;
    }

    private static final class Harness {
        final RecordingGateway gateway;
        final ToolExecutionLedgerApplicationApi ledgerApi;
        final SandboxToolExecutionApplicationApi api;

        Harness(RecordingGateway gateway) {
            this.gateway = gateway;
            this.ledgerApi = new ToolExecutionLedgerService(
                    new InMemoryToolExecutionLedgerRepository(),
                    sequentialIdGenerator("ledger"),
                    fixedTimeProvider());
            this.api = new SandboxToolExecutionService(
                    ledgerApi,
                    gateway,
                    sequentialIdGenerator("tool"),
                    new ObjectMapper());
        }
    }

    private static final class RecordingGateway implements SandboxExecutionGateway {
        private final AtomicInteger calls = new AtomicInteger();
        private final boolean unavailable;

        private RecordingGateway(boolean unavailable) {
            this.unavailable = unavailable;
        }

        static RecordingGateway succeeding() {
            return new RecordingGateway(false);
        }

        static RecordingGateway unavailable() {
            return new RecordingGateway(true);
        }

        int calls() {
            return calls.get();
        }

        @Override
        public SandboxExecutionResponse execute(SandboxExecutionRequest request) {
            calls.incrementAndGet();
            if (unavailable) {
                throw new SandboxExecutionUnavailableException("sandbox worker unavailable");
            }
            String output = String.join(" ", request.arguments()) + "\n";
            long now = System.currentTimeMillis();
            return new SandboxExecutionResponse(
                    request.executionId(),
                    request.agentRunId(),
                    request.toolCallId(),
                    0,
                    SandboxExecutionStatus.SUCCEEDED,
                    output,
                    "",
                    List.of(),
                    new SandboxExecutionMetadata(now, now, 0, false, output.length()),
                    null);
        }
    }
}
