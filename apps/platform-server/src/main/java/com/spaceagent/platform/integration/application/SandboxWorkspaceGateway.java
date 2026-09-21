package com.spaceagent.platform.integration.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.project.api.WorkspaceSandboxExecutionException;
import com.spaceagent.platform.project.domain.Workspace;
import com.spaceagent.platform.project.domain.WorkspaceCodingGateway;
import com.spaceagent.platform.project.domain.WorkspaceSandboxGateway;
import com.spaceagent.platform.tooling.api.SandboxComputeApplicationApi;
import com.spaceagent.platform.tooling.domain.SandboxExecutionUnavailableException;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class SandboxWorkspaceGateway implements WorkspaceSandboxGateway {
    private static final String EXECUTABLE = "spaceagent-workspace-tool";
    private static final int PREPARED_BUNDLE_CHUNK_BYTES = 700_000;
    private final SandboxComputeApplicationApi sandbox;
    private final ObjectMapper json;

    public SandboxWorkspaceGateway(SandboxComputeApplicationApi sandbox, ObjectMapper json) {
        this.sandbox = sandbox;
        this.json = json;
    }

    @Override
    public FileRead readFile(Workspace workspace, Execution execution, String path, int maximum) {
        JsonNode node = helper(workspace, execution, "workspace-read-file",
                List.of("file-read", path, Integer.toString(maximum)), null, 60);
        return new FileRead(text(node, "path"), text(node, "content"),
                node.path("sizeBytes").asLong(), node.path("truncated").asBoolean());
    }

    @Override
    public FileList listFiles(
            Workspace workspace, Execution execution, String path, int depth, int maximum) {
        JsonNode node = helper(workspace, execution, "workspace-read-list",
                List.of("file-list", path == null || path.isBlank() ? "." : path,
                        Integer.toString(depth), Integer.toString(maximum)), null, 60);
        List<Entry> entries = new ArrayList<>();
        node.path("entries").forEach(value -> entries.add(new Entry(
                text(value, "path"), value.path("directory").asBoolean(),
                value.path("sizeBytes").asLong())));
        return new FileList(text(node, "root"), entries, node.path("truncated").asBoolean());
    }

    @Override
    public Snapshot snapshot(Workspace workspace, Execution execution, int maximum) {
        JsonNode node = helper(workspace, execution, "workspace-read-git",
                List.of("git", Integer.toString(maximum)), null, 60);
        List<String> changed = new ArrayList<>();
        node.path("changedFiles").forEach(value -> changed.add(value.asText()));
        return new Snapshot(text(node, "headCommit"), text(node, "status"),
                text(node, "patch"), changed, node.path("truncated").asBoolean());
    }

    @Override
    public DocumentRead readDocument(
            Workspace workspace, Execution execution, String path, int maximum) {
        JsonNode node = helper(workspace, execution, "workspace-read-document",
                List.of("document-read", path, Integer.toString(maximum)), null, 60);
        Map<String, String> metadata = new LinkedHashMap<>();
        node.path("metadata").fields().forEachRemaining(entry ->
                metadata.put(entry.getKey(), entry.getValue().asText()));
        return new DocumentRead(text(node, "path"), text(node, "mediaType"),
                text(node, "content"), metadata, node.path("truncated").asBoolean());
    }

    @Override
    public DocumentWrite writeDocument(
            Workspace workspace, Execution execution, String path, String content, String format) {
        JsonNode node = helper(workspace, execution, "workspace-write-document",
                List.of("document-write", path, format), encode(content), 60);
        return new DocumentWrite(text(node, "path"), text(node, "format"),
                node.path("sizeBytes").asLong(), strings(node.path("changedFiles")));
    }

    @Override
    public CodingResult execute(
            Workspace workspace, Execution execution, WorkspaceCodingGateway.CodingOperation operation) {
        if (operation.type() == WorkspaceCodingGateway.Type.RUN_COMMAND) {
            try {
                var result = sandbox.execute(command(workspace, execution, "coding-run-command",
                        operation.executable(), operation.arguments(), operation.timeoutSeconds(), null));
                return new CodingResult(result.exitStatus(), result.stdout(), result.stderr(),
                        List.of(), result.error(), false);
            } catch (SandboxExecutionUnavailableException error) {
                return new CodingResult(-1, "", "", List.of(),
                        "SANDBOX_OUTCOME_UNKNOWN", true);
            }
        }
        String verb = operation.type() == WorkspaceCodingGateway.Type.WRITE_FILE
                ? "write-file" : "delete-file";
        try {
            JsonNode node = helper(workspace, execution,
                    operation.type() == WorkspaceCodingGateway.Type.WRITE_FILE
                            ? "workspace-write-file" : "workspace-delete-file",
                    List.of(verb, operation.relativePath()),
                    operation.type() == WorkspaceCodingGateway.Type.WRITE_FILE
                            ? encode(operation.content()) : null,
                    operation.timeoutSeconds());
            return new CodingResult(0, "", "", strings(node.path("changedFiles")), null, false);
        } catch (WorkspaceSandboxExecutionException error) {
            return new CodingResult(-1, "", "", List.of(), error.safeCode(), error.ambiguous());
        }
    }

    @Override
    public PreparedCommit prepareCommit(
            Workspace workspace, Execution execution, String expectedBase,
            String patchHash, String commitMessage) {
        JsonNode node = helper(workspace, execution, "workspace-prepare-commit",
                List.of("prepare-commit", expectedBase, patchHash), encode(commitMessage), 120);
        try {
            String commit = text(node, "commit");
            String reference = text(node, "bundleReference");
            String expectedHash = text(node, "bundleSha256");
            long expectedBytes = positiveLong(node, "bundleBytes");
            if (!commit.matches("[0-9a-f]{40,64}")
                    || !reference.equals(".git/spaceagent-transfer/" + commit + ".bundle")
                    || !expectedHash.matches("sha256:[0-9a-f]{64}")
                    || expectedBytes > MAX_PREPARED_BUNDLE_BYTES) {
                throw invalidBundle();
            }
            byte[] bundle = readPreparedBundle(
                    workspace, execution, commit, reference, expectedBytes);
            String actualHash = "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bundle));
            if (!actualHash.equals(expectedHash)) {
                throw invalidBundle();
            }
            return new PreparedCommit(
                    commit, reference, expectedHash, expectedBytes, bundle);
        } catch (WorkspaceSandboxExecutionException error) {
            throw error;
        } catch (Exception error) {
            throw new WorkspaceSandboxExecutionException(
                    "WORKSPACE_SANDBOX_RESPONSE_INVALID", true);
        }
    }

    private byte[] readPreparedBundle(
            Workspace workspace,
            Execution execution,
            String commit,
            String reference,
            long expectedBytes) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.toIntExact(expectedBytes));
        long offset = 0;
        while (offset < expectedBytes) {
            int maximum = (int) Math.min(PREPARED_BUNDLE_CHUNK_BYTES, expectedBytes - offset);
            JsonNode node = helper(
                    workspace, execution, "workspace-read-prepared-bundle",
                    List.of("prepared-bundle-read", commit, Long.toString(offset),
                            Integer.toString(maximum)), null, 60);
            if (!commit.equals(text(node, "commit"))
                    || !reference.equals(text(node, "bundleReference"))
                    || nonNegativeLong(node, "offset") != offset
                    || positiveLong(node, "totalBytes") != expectedBytes) {
                throw invalidBundle();
            }
            byte[] chunk;
            try {
                chunk = Base64.getDecoder().decode(text(node, "chunkBase64"));
            } catch (RuntimeException error) {
                throw invalidBundle();
            }
            if (chunk.length != maximum) {
                throw invalidBundle();
            }
            output.writeBytes(chunk);
            offset += chunk.length;
        }
        byte[] value = output.toByteArray();
        if (value.length != expectedBytes) throw invalidBundle();
        return value;
    }

    private JsonNode helper(
            Workspace workspace, Execution execution, String tool, List<String> arguments,
            String input, int timeout) {
        try {
            var result = sandbox.execute(command(
                    workspace, execution, tool, EXECUTABLE, arguments, timeout, input));
            if (!"SUCCEEDED".equals(result.status()) || result.exitStatus() != 0) {
                throw new WorkspaceSandboxExecutionException(
                        result.error() == null ? "WORKSPACE_SANDBOX_FAILED" : result.error(),
                        false);
            }
            JsonNode node = json.readTree(result.stdout());
            if (node == null || !node.isObject()) {
                throw new WorkspaceSandboxExecutionException("WORKSPACE_SANDBOX_RESPONSE_INVALID", true);
            }
            return node;
        } catch (WorkspaceSandboxExecutionException error) {
            throw error;
        } catch (SandboxExecutionUnavailableException error) {
            throw new WorkspaceSandboxExecutionException("SANDBOX_OUTCOME_UNKNOWN", true);
        } catch (Exception error) {
            throw new WorkspaceSandboxExecutionException("WORKSPACE_SANDBOX_RESPONSE_INVALID", true);
        }
    }

    private SandboxComputeApplicationApi.ComputeCommand command(
            Workspace workspace, Execution execution, String tool, String executable,
            List<String> arguments, int timeout, String input) {
        return new SandboxComputeApplicationApi.ComputeCommand(
                execution.agentRunId(), execution.toolCallId(), "workspaces/" + workspace.id(),
                workspace.taskId(), tool, executable, arguments,
                Math.max(1, Math.min(timeout, 600)), input);
    }

    private static String encode(String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 500_000) {
            throw new WorkspaceSandboxExecutionException("WORKSPACE_INPUT_TOO_LARGE", false);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            throw new WorkspaceSandboxExecutionException("WORKSPACE_SANDBOX_RESPONSE_INVALID", true);
        }
        return value.asText();
    }

    private static List<String> strings(JsonNode node) {
        if (!node.isArray()) {
            throw new WorkspaceSandboxExecutionException("WORKSPACE_SANDBOX_RESPONSE_INVALID", true);
        }
        List<String> result = new ArrayList<>();
        node.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private static long positiveLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) {
            throw new WorkspaceSandboxExecutionException(
                    "WORKSPACE_SANDBOX_RESPONSE_INVALID", true);
        }
        return value.longValue();
    }

    private static long nonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
            throw invalidBundle();
        }
        return value.longValue();
    }

    private static WorkspaceSandboxExecutionException invalidBundle() {
        return new WorkspaceSandboxExecutionException(
                "WORKSPACE_PREPARED_BUNDLE_INVALID", true);
    }
}
