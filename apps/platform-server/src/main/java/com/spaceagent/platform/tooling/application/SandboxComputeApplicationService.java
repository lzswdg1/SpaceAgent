package com.spaceagent.platform.tooling.application;

import com.spaceagent.platform.tooling.api.SandboxComputeApplicationApi;
import com.spaceagent.platform.tooling.domain.SandboxExecutionGateway;
import com.spaceagent.platform.tooling.domain.SandboxExecutionRequest;
import com.spaceagent.platform.tooling.domain.SandboxResourcePolicy;
import com.spaceagent.shared.id.IdGenerator;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class SandboxComputeApplicationService implements SandboxComputeApplicationApi {
    private static final Set<String> ALLOWED_EXECUTABLES = Set.of(
            "./mvnw", "mvn", "npm", "pnpm", "go", "git", "spaceagent-workspace-tool");
    private static final int MAX_OUTPUT_BYTES = 200_000;
    private static final int MAX_RECOVERY_OUTPUT_BYTES = 1_000_000;
    private static final long MAX_MEMORY_BYTES = 512L * 1024 * 1024;

    private final SandboxExecutionGateway gateway;
    private final IdGenerator ids;

    public SandboxComputeApplicationService(
            SandboxExecutionGateway gateway,
            IdGenerator ids) {
        this.gateway = gateway;
        this.ids = ids;
    }

    @Override
    public ComputeResult execute(ComputeCommand command) {
        validate(command);
        int timeout = Math.max(1, Math.min(command.timeoutSeconds(), 600));
        boolean readOnlyWorkspace = "project-context-snapshot".equals(command.tool())
                || "project-intake-inspection".equals(command.tool())
                || command.tool().startsWith("workspace-read-")
                || command.tool().startsWith("document-workspace-read-");
        int maxOutputBytes = readOnlyWorkspace
                ? MAX_RECOVERY_OUTPUT_BYTES : MAX_OUTPUT_BYTES;
        var response = gateway.execute(new SandboxExecutionRequest(
                ids.nextId(),
                command.agentRunId(),
                command.toolCallId(),
                command.workspaceRef(),
                command.taskRef(),
                command.tool(),
                command.executable(),
                command.arguments(),
                timeout,
                new SandboxResourcePolicy(
                        maxOutputBytes,
                        timeout,
                        MAX_MEMORY_BYTES,
                        false,
                        List.of("."),
                        readOnlyWorkspace),
                "{}",
                command.inputBase64(), command.sourceRef()));
        if (!command.agentRunId().equals(response.agentRunId())
                || !command.toolCallId().equals(response.toolCallId())
                || response.metadata() == null) {
            throw new IllegalStateException("Sandbox result correlation failed");
        }
        return new ComputeResult(
                response.status().name(),
                response.exitStatus(),
                bound(response.stdout(), maxOutputBytes),
                bound(response.stderr(), maxOutputBytes),
                response.metadata().timedOut(),
                Math.max(0, response.metadata().wallTimeMs()),
                Math.max(0, response.metadata().outputBytes()),
                safeError(response.error()));
    }

    private static void validate(ComputeCommand command) {
        if (command == null
                || blank(command.agentRunId())
                || blank(command.toolCallId())
                || blank(command.workspaceRef())
                || blank(command.taskRef())
                || blank(command.tool())
                || !ALLOWED_EXECUTABLES.contains(command.executable())
                || command.arguments().size() > 100
                || command.arguments().stream().anyMatch(
                value -> value == null || value.length() > 4_096 || value.indexOf('\0') >= 0)
                || command.timeoutSeconds() < 1
                || command.timeoutSeconds() > 600) {
            throw new IllegalArgumentException("Sandbox compute command is invalid");
        }
        if (command.inputBase64() != null
                && (command.inputBase64().length() > 700_000
                || !command.inputBase64().matches("[A-Za-z0-9+/]*={0,2}"))) {
            throw new IllegalArgumentException("Sandbox input is invalid");
        }
        if (!command.workspaceRef().matches(
                "^(workspaces|document-workspaces)/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
                        + "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")) {
            throw new IllegalArgumentException("Sandbox Workspace reference is invalid");
        }
        boolean materialize = "managed-snapshot-materialize".equals(command.tool());
        if (materialize != (command.sourceRef() != null && command.sourceRef().matches(
                "^sources/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
                        + "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"))) {
            throw new IllegalArgumentException("Sandbox Source snapshot reference is invalid");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String bound(String value, int maxOutputBytes) {
        String safe = value == null ? "" : value;
        return safe.substring(0, Math.min(safe.length(), maxOutputBytes));
    }

    private static String safeError(String value) {
        if (value == null || value.isBlank()) return null;
        String safe = value.trim();
        if (!safe.matches("[A-Z0-9_-]{1,120}")) return "SANDBOX_FAILED";
        return safe;
    }
}
