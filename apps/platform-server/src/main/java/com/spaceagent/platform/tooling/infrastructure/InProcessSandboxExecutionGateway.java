package com.spaceagent.platform.tooling.infrastructure;

import com.spaceagent.platform.tooling.domain.SandboxExecutionGateway;
import com.spaceagent.platform.tooling.domain.SandboxExecutionMetadata;
import com.spaceagent.platform.tooling.domain.SandboxExecutionRequest;
import com.spaceagent.platform.tooling.domain.SandboxExecutionResponse;
import com.spaceagent.platform.tooling.domain.SandboxExecutionStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Safe in-process fallback used when the Python sandbox worker is not configured.
 *
 * <p>It deliberately executes only a tiny deterministic subset ({@code echo} and
 * {@code fail}) rather than arbitrary code. Real isolated execution is delegated to
 * {@link HttpSandboxExecutionGateway}.
 */
@Component
@ConditionalOnProperty(prefix = "platform.sandbox", name = "mode", havingValue = "in-process", matchIfMissing = true)
public class InProcessSandboxExecutionGateway implements SandboxExecutionGateway {

    @Override
    public SandboxExecutionResponse execute(SandboxExecutionRequest request) {
        long startedAt = System.currentTimeMillis();
        String command = request.command();
        if ("echo".equals(command)) {
            String output = String.join(" ", request.arguments()) + "\n";
            return new SandboxExecutionResponse(
                    request.executionId(),
                    request.agentRunId(),
                    request.toolCallId(),
                    0,
                    SandboxExecutionStatus.SUCCEEDED,
                    output,
                    "",
                    List.of(),
                    metadata(startedAt, output.length(), false),
                    null);
        }
        if ("fail".equals(command)) {
            return new SandboxExecutionResponse(
                    request.executionId(),
                    request.agentRunId(),
                    request.toolCallId(),
                    1,
                    SandboxExecutionStatus.FAILED,
                    "",
                    "in-process sandbox command failed",
                    List.of(),
                    metadata(startedAt, 0, false),
                    "in-process sandbox command failed");
        }
        return new SandboxExecutionResponse(
                request.executionId(),
                request.agentRunId(),
                request.toolCallId(),
                -1,
                SandboxExecutionStatus.REJECTED,
                "",
                "in-process sandbox only supports echo and fail",
                List.of(),
                metadata(startedAt, 0, false),
                "unsupported in-process sandbox command: " + command);
    }

    private SandboxExecutionMetadata metadata(long startedAt, long outputBytes, boolean timedOut) {
        long completedAt = System.currentTimeMillis();
        return new SandboxExecutionMetadata(
                startedAt,
                completedAt,
                completedAt - startedAt,
                timedOut,
                outputBytes);
    }
}
