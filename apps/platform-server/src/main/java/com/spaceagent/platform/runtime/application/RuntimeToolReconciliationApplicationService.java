package com.spaceagent.platform.runtime.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.project.api.WorkspaceSandboxExecutionException;
import com.spaceagent.platform.project.api.WorkspaceToolApplicationApi;
import com.spaceagent.platform.knowledge.api.DocumentWorkspaceToolApplicationApi;
import com.spaceagent.platform.runtime.api.AgentRunView;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeToolReconciliationApplicationApi;
import com.spaceagent.platform.runtime.domain.AgentRunConfigurationSnapshot;
import com.spaceagent.platform.runtime.domain.AgentRunConfigurationSnapshotRepository;
import com.spaceagent.platform.tooling.api.ReconcileUnknownToolExecutionCommand;
import com.spaceagent.platform.tooling.api.ToolEffectVerificationApplicationApi;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerView;
import com.spaceagent.platform.tooling.domain.ToolEffectVerifier;
import com.spaceagent.platform.tooling.domain.ToolExecutionReconciliationEvidence;
import com.spaceagent.platform.tooling.domain.ToolExecutionStatus;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RuntimeToolReconciliationApplicationService
        implements RuntimeToolReconciliationApplicationApi {
    private static final int MAX_VERIFY_CHARACTERS = 200_000;

    private final RuntimeApplicationApi runtime;
    private final ToolExecutionLedgerApplicationApi ledger;
    private final WorkspaceToolApplicationApi workspace;
    private final ObjectMapper json;
    private final ToolEffectVerificationApplicationApi effectVerification;
    private final DocumentWorkspaceToolApplicationApi documentWorkspaces;
    private final AgentRunConfigurationSnapshotRepository runConfigurations;

    public RuntimeToolReconciliationApplicationService(
            RuntimeApplicationApi runtime,
            ToolExecutionLedgerApplicationApi ledger,
            WorkspaceToolApplicationApi workspace,
            ObjectMapper json) {
        this(runtime, ledger, workspace, json, null, null, null);
    }

    public RuntimeToolReconciliationApplicationService(
            RuntimeApplicationApi runtime,
            ToolExecutionLedgerApplicationApi ledger,
            WorkspaceToolApplicationApi workspace,
            ObjectMapper json,
            ToolEffectVerificationApplicationApi effectVerification) {
        this(runtime,ledger,workspace,json,effectVerification,null,null);
    }

    public RuntimeToolReconciliationApplicationService(
            RuntimeApplicationApi runtime, ToolExecutionLedgerApplicationApi ledger,
            WorkspaceToolApplicationApi workspace, ObjectMapper json,
            ToolEffectVerificationApplicationApi effectVerification,
            DocumentWorkspaceToolApplicationApi documentWorkspaces) {
        this(runtime, ledger, workspace, json, effectVerification, documentWorkspaces, null);
    }

    @Autowired
    public RuntimeToolReconciliationApplicationService(
            RuntimeApplicationApi runtime, ToolExecutionLedgerApplicationApi ledger,
            WorkspaceToolApplicationApi workspace, ObjectMapper json,
            ToolEffectVerificationApplicationApi effectVerification,
            DocumentWorkspaceToolApplicationApi documentWorkspaces,
            AgentRunConfigurationSnapshotRepository runConfigurations) {
        this.runtime = runtime;
        this.ledger = ledger;
        this.workspace = workspace;
        this.json = json;
        this.effectVerification = effectVerification;
        this.documentWorkspaces = documentWorkspaces;
        this.runConfigurations = runConfigurations;
    }

    @Override
    public ReconciliationView reconcile(ReconcileCommand command) {
        AgentRunView run = runtime.findRun(command.agentRunId())
                .filter(value -> command.tenantId().equals(value.tenantId()))
                .filter(value -> command.userId().equals(value.ownerId()))
                .orElseThrow(() -> business(
                        "AgentRun not found", "TOOL_RECONCILIATION_RUN_NOT_FOUND",
                        HttpStatus.NOT_FOUND));
        ToolExecutionLedgerView unknown = ledger.findByRunId(run.id()).stream()
                .filter(value -> command.toolCallId().equals(value.toolCallId()))
                .findFirst()
                .orElseThrow(() -> business(
                        "Tool execution not found", "TOOL_RECONCILIATION_NOT_FOUND",
                        HttpStatus.NOT_FOUND));
        if (unknown.status() == ToolExecutionStatus.SUCCEEDED
                && unknown.resolvedAt() != null) {
            return new ReconciliationView(
                    run.id(), unknown.toolCallId(), unknown.toolName(),
                    "CURRENT_TERMINAL", unknown.status().name(), unknown.revision(),
                    "existing_reconciliation", Map.of("postcondition", "already_resolved"),
                    unknown.resolvedAt());
        }
        if (unknown.status() != ToolExecutionStatus.UNKNOWN) {
            throw business(
                    "Tool execution is not UNKNOWN", "TOOL_RECONCILIATION_STATE_CONFLICT",
                    HttpStatus.CONFLICT);
        }
        if (unknown.revision() != command.expectedRevision()) {
            throw business(
                    "Tool execution revision changed", "TOOL_RECONCILIATION_REVISION_CONFLICT",
                    HttpStatus.CONFLICT);
        }

        if (effectVerification != null && ("mcp_call".equals(unknown.toolName())
                || "workspace-run_command".equals(unknown.toolName()))) {
            return reconcileCapability(command, run, unknown);
        }
        if(documentWorkspaces!=null&&unknown.toolName().startsWith("document-workspace-")){
            var verified=documentWorkspaces.reconcile(new DocumentWorkspaceToolApplicationApi.ReconcileCommand(
                    command.tenantId(),command.userId(),run.id(),unknown.toolCallId(),
                    "reconcile:"+sha256(unknown.toolCallId()).substring(0,20),command.reason()));
            ToolExecutionLedgerView resolved=ledger.findByRunId(run.id()).stream().filter(v->v.toolCallId().equals(unknown.toolCallId())).findFirst().orElseThrow();
            return new ReconciliationView(run.id(),resolved.toolCallId(),resolved.toolName(),"APPLIED",
                    resolved.status().name(),resolved.revision(),verified.verifier(),verified.evidence(),resolved.resolvedAt());
        }

        Verification verification;
        try {
            verification = verify(run, command.userId(), unknown);
        } catch (WorkspaceSandboxExecutionException error) {
            throw inconclusive();
        }
        String reason = bound(command.reason(), 1_000);
        Map<String, String> details = new LinkedHashMap<>(verification.evidence());
        details.put("verifier", verification.verifier());
        var transition = ledger.reconcileUnknown(new ReconcileUnknownToolExecutionCommand(
                run.id(), unknown.toolCallId(), unknown.inputHash(), unknown.revision(),
                ToolExecutionStatus.SUCCEEDED,
                safeResult(verification.verifier()), null, null,
                new ToolExecutionReconciliationEvidence(
                        "verified desired Workspace postcondition", null, details),
                command.userId(), reason));
        ToolExecutionLedgerView resolved = transition.ledger();
        if (resolved.status() != ToolExecutionStatus.SUCCEEDED) {
            throw business(
                    "Tool reconciliation lost the UNKNOWN revision",
                    "TOOL_RECONCILIATION_REVISION_CONFLICT", HttpStatus.CONFLICT);
        }
        return new ReconciliationView(
                run.id(), resolved.toolCallId(), resolved.toolName(),
                transition.type().name(), resolved.status().name(), resolved.revision(),
                verification.verifier(), details, resolved.resolvedAt());
    }

    private ReconciliationView reconcileCapability(
            ReconcileCommand command,
            AgentRunView run,
            ToolExecutionLedgerView unknown) {
        ToolEffectVerifier.Scope scope = "mcp_call".equals(unknown.toolName())
                ? mcpScope(run, unknown) : commandScope(run, unknown);
        var verified = effectVerification.verify(
                new ToolEffectVerificationApplicationApi.VerifyCommand(
                        command.tenantId(), command.userId(), run.id(), unknown.toolCallId(),
                        unknown.revision(), command.reason(), scope));
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("verifierId", verified.verifierId());
        evidence.put("verifierVersion", Integer.toString(verified.verifierVersion()));
        evidence.put("verdict", verified.verdict());
        evidence.put("subjectSha256", verified.subjectSha256());
        if (verified.observationSha256() != null) {
            evidence.put("observationSha256", verified.observationSha256());
        }
        evidence.put("safeCode", verified.safeCode());
        return new ReconciliationView(
                run.id(), unknown.toolCallId(), unknown.toolName(), verified.transition(),
                verified.ledgerStatus(), verified.ledgerRevision(), verified.verifierId(),
                Map.copyOf(evidence), verified.resolvedAt());
    }

    private ToolEffectVerifier.McpScope mcpScope(
            AgentRunView run, ToolExecutionLedgerView unknown) {
        Map<String, Object> input = arguments(unknown.arguments());
        String connectionId = required(input, "connectionId");
        String remoteTool = required(input, "remoteTool");
        if (runConfigurations != null) {
            AgentRunConfigurationSnapshot snapshot = runConfigurations.findByRunId(
                            run.tenantId(), run.ownerId(), run.id())
                    .filter(value -> value.state()
                            == AgentRunConfigurationSnapshot.State.SNAPSHOTTED)
                    .orElseThrow(RuntimeToolReconciliationApplicationService::unsupported);
            var binding = snapshot.mcpBindings().stream()
                    .filter(value -> value.connectionId().equals(connectionId))
                    .filter(value -> value.allowedToolNames().contains(remoteTool))
                    .findFirst().orElseThrow(RuntimeToolReconciliationApplicationService::unsupported);
            return new ToolEffectVerifier.McpScope(
                    binding.connectionId(), binding.connectionRevision(),
                    binding.capabilitySnapshotId(), binding.snapshotSha256(), remoteTool);
        }
        throw unsupported();
    }

    private ToolEffectVerifier.SandboxCommandScope commandScope(
            AgentRunView run, ToolExecutionLedgerView unknown) {
        if (run.workspaceId() == null || run.taskId() == null) throw unsupported();
        Map<String, Object> input = arguments(unknown.arguments());
        if (!"RUN_COMMAND".equals(required(input, "type"))) throw unsupported();
        String executable = required(input, "executable");
        Object raw = input.get("arguments");
        if (!(raw instanceof List<?> values)
                || values.stream().anyMatch(value -> !(value instanceof String))) {
            throw unsupported();
        }
        List<String> commandArguments = values.stream().map(String.class::cast).toList();
        return new ToolEffectVerifier.SandboxCommandScope(
                run.workspaceId(), "workspaces/" + run.workspaceId(), run.taskId(),
                ToolEffectVerifier.commandDigest(executable, commandArguments),
                executable);
    }

    private Verification verify(
            AgentRunView run, String userId, ToolExecutionLedgerView unknown) {
        Map<String, Object> arguments = arguments(unknown.arguments());
        return switch (unknown.toolName()) {
            case "document_write" -> verifyDocumentWrite(run, userId, arguments);
            case "workspace-write_file" -> verifyWorkspaceWrite(run, userId, arguments);
            case "workspace-delete_file" -> verifyWorkspaceDelete(run, userId, arguments);
            default -> throw business(
                    "Tool has no trustworthy postcondition verifier",
                    "TOOL_RECONCILIATION_UNSUPPORTED", HttpStatus.CONFLICT);
        };
    }

    private Verification verifyDocumentWrite(
            AgentRunView run, String userId, Map<String, Object> arguments) {
        String workspaceId = required(arguments, "workspaceId");
        if (!workspaceId.equals(requiredWorkspace(run))) {
            throw business(
                    "Persisted Tool Workspace does not match the Run binding",
                    "TOOL_RECONCILIATION_SCOPE_MISMATCH", HttpStatus.CONFLICT);
        }
        String path = required(arguments, "path");
        String expected = required(arguments, "content");
        String format = required(arguments, "format");
        WorkspaceToolApplicationApi.Scope scope = scope(run, userId, workspaceId);
        if ("docx".equals(format)) {
            var actual = workspace.readDocument(new WorkspaceToolApplicationApi.DocumentReadQuery(
                    scope, path, MAX_VERIFY_CHARACTERS));
            String normalized = String.join("\n", expected.lines().toList());
            requireContentMatch(normalized, actual.content(), actual.truncated());
            return verified("document_docx_text_sha256", normalized, actual.content());
        }
        if (!List.of("text", "markdown", "html").contains(format)) {
            throw unsupported();
        }
        var actual = workspace.readFile(new WorkspaceToolApplicationApi.FileReadQuery(
                scope, path, MAX_VERIFY_CHARACTERS));
        requireFileMatch(expected, actual);
        return verified("document_utf8_sha256", expected, actual.content());
    }

    private Verification verifyWorkspaceWrite(
            AgentRunView run, String userId, Map<String, Object> arguments) {
        if (!"WRITE_FILE".equals(required(arguments, "type"))) {
            throw business(
                    "Persisted Coding Tool type does not match its ledger name",
                    "TOOL_RECONCILIATION_INPUT_INVALID", HttpStatus.CONFLICT);
        }
        String expected = required(arguments, "content");
        String path = required(arguments, "path");
        var actual = workspace.readFile(new WorkspaceToolApplicationApi.FileReadQuery(
                scope(run, userId, requiredWorkspace(run)), path, MAX_VERIFY_CHARACTERS));
        requireFileMatch(expected, actual);
        return verified("workspace_file_utf8_sha256", expected, actual.content());
    }

    private Verification verifyWorkspaceDelete(
            AgentRunView run, String userId, Map<String, Object> arguments) {
        if (!"DELETE_FILE".equals(required(arguments, "type"))) {
            throw business(
                    "Persisted Coding Tool type does not match its ledger name",
                    "TOOL_RECONCILIATION_INPUT_INVALID", HttpStatus.CONFLICT);
        }
        String path = required(arguments, "path");
        try {
            workspace.readFile(new WorkspaceToolApplicationApi.FileReadQuery(
                    scope(run, userId, requiredWorkspace(run)), path, 1_000));
        } catch (WorkspaceSandboxExecutionException error) {
            if (!error.ambiguous() && "WORKSPACE_FILE_NOT_FOUND".equals(error.safeCode())) {
                return new Verification(
                        "workspace_file_absent",
                        Map.of("postcondition", "absent", "pathSha256", sha256(path)));
            }
            throw inconclusive();
        }
        throw inconclusive();
    }

    private WorkspaceToolApplicationApi.Scope scope(
            AgentRunView run, String userId, String workspaceId) {
        if (run.projectId() == null || run.taskId() == null) throw unsupported();
        return new WorkspaceToolApplicationApi.Scope(
                run.tenantId(), userId, run.projectId(), run.taskId(), workspaceId,
                run.id(), "reconcile:" + sha256(run.id()).substring(0, 20));
    }

    private void requireFileMatch(
            String expected, WorkspaceToolApplicationApi.FileReadView actual) {
        if (expected.length() > MAX_VERIFY_CHARACTERS
                || actual.truncated()
                || actual.sizeBytes() != expected.getBytes(StandardCharsets.UTF_8).length
                || !expected.equals(actual.content())) {
            throw inconclusive();
        }
    }

    private void requireContentMatch(String expected, String actual, boolean truncated) {
        if (expected.length() > MAX_VERIFY_CHARACTERS
                || truncated || !expected.equals(actual)) {
            throw inconclusive();
        }
    }

    private Verification verified(String verifier, String expected, String actual) {
        return new Verification(verifier, Map.of(
                "expectedSha256", sha256(expected),
                "actualSha256", sha256(actual),
                "postcondition", "matched"));
    }

    private Map<String, Object> arguments(String value) {
        try {
            return json.readValue(value, new TypeReference<Map<String, Object>>() { });
        } catch (Exception error) {
            throw business(
                    "Persisted Tool arguments are invalid",
                    "TOOL_RECONCILIATION_INPUT_INVALID", HttpStatus.CONFLICT);
        }
    }

    private String safeResult(String verifier) {
        try {
            return json.writeValueAsString(Map.of("verified", true, "verifier", verifier));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize reconciliation result");
        }
    }

    private static String required(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw business(
                    "Persisted Tool arguments are incomplete",
                    "TOOL_RECONCILIATION_INPUT_INVALID", HttpStatus.CONFLICT);
        }
        return String.valueOf(value);
    }

    private static String requiredWorkspace(AgentRunView run) {
        if (run.workspaceId() == null || run.workspaceId().isBlank()) throw unsupported();
        return run.workspaceId();
    }

    private static BusinessException unsupported() {
        return business(
                "Tool has no trustworthy postcondition verifier",
                "TOOL_RECONCILIATION_UNSUPPORTED", HttpStatus.CONFLICT);
    }

    private static BusinessException inconclusive() {
        return business(
                "Tool postcondition could not be proven",
                "TOOL_RECONCILIATION_INCONCLUSIVE", HttpStatus.CONFLICT);
    }

    private static String bound(String value, int maximum) {
        String normalized = value.trim();
        return normalized.substring(0, Math.min(maximum, normalized.length()));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static BusinessException business(String message, String code, HttpStatus status) {
        return new BusinessException(message, status, code);
    }

    private record Verification(String verifier, Map<String, String> evidence) {
    }
}
