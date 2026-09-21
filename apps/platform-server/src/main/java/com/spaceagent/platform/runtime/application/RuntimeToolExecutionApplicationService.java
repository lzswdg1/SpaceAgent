package com.spaceagent.platform.runtime.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.governance.api.GovernanceApplicationApi;
import com.spaceagent.platform.governance.api.GovernanceApprovalRequiredException;
import com.spaceagent.platform.governance.domain.GovernanceActionType;
import com.spaceagent.platform.knowledge.api.KnowledgeApplicationApi;
import com.spaceagent.platform.knowledge.api.KnowledgeRetrievalCommand;
import com.spaceagent.platform.knowledge.api.DocumentWorkspaceToolApplicationApi;
import com.spaceagent.platform.project.api.WorkspaceToolApplicationApi;
import com.spaceagent.platform.project.api.WorkspaceSandboxExecutionException;
import com.spaceagent.platform.project.domain.WorkspaceCodingGateway;
import com.spaceagent.platform.runtime.api.AgentRunView;
import com.spaceagent.platform.runtime.api.CodingRuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeToolExecutionApplicationApi;
import com.spaceagent.platform.runtime.domain.AgentRunConfigurationSnapshot;
import com.spaceagent.platform.runtime.domain.AgentRunConfigurationSnapshotRepository;
import com.spaceagent.platform.runtime.domain.RuntimeOperationalTelemetry;
import com.spaceagent.platform.tooling.api.ClaimToolExecutionCommand;
import com.spaceagent.platform.tooling.api.CompleteToolExecutionCommand;
import com.spaceagent.platform.tooling.api.ExternalRuntimeToolApplicationApi;
import com.spaceagent.platform.tooling.api.MarkToolExecutionUnknownCommand;
import com.spaceagent.platform.tooling.api.McpBindingValidationApplicationApi;
import com.spaceagent.platform.tooling.api.RuntimeCapabilityCatalogApplicationApi;
import com.spaceagent.platform.tooling.api.SandboxToolExecutionApplicationApi;
import com.spaceagent.platform.tooling.api.SandboxToolExecutionCommand;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerView;
import com.spaceagent.platform.tooling.domain.ToolExecutionClaimDecisionType;
import com.spaceagent.platform.tooling.domain.ToolExecutionStatus;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
public class RuntimeToolExecutionApplicationService
        implements RuntimeToolExecutionApplicationApi {
    private static final int MAX_RESULT_CHARACTERS = 200_000;
    private final RuntimeApplicationApi runtime;
    private final AgentRunConfigurationSnapshotRepository runConfigurations;
    private final McpBindingValidationApplicationApi mcpValidation;
    private final RuntimeCapabilityCatalogApplicationApi catalog;
    private final ToolExecutionLedgerApplicationApi ledger;
    private final SandboxToolExecutionApplicationApi sandbox;
    private final ExternalRuntimeToolApplicationApi external;
    private final KnowledgeApplicationApi knowledge;
    private final DocumentWorkspaceToolApplicationApi documentWorkspaces;
    private final WorkspaceToolApplicationApi workspace;
    private final CodingRuntimeApplicationApi coding;
    private final GovernanceApplicationApi governance;
    private final ObjectMapper json;
    private final RuntimeOperationalTelemetry telemetry;

    public RuntimeToolExecutionApplicationService(
            RuntimeApplicationApi runtime,
            RuntimeCapabilityCatalogApplicationApi catalog,
            ToolExecutionLedgerApplicationApi ledger,
            SandboxToolExecutionApplicationApi sandbox,
            ExternalRuntimeToolApplicationApi external,
            KnowledgeApplicationApi knowledge,
            WorkspaceToolApplicationApi workspace,
            CodingRuntimeApplicationApi coding,
            GovernanceApplicationApi governance,
            ObjectMapper json) {
        this(runtime, catalog, ledger, sandbox, external, knowledge, workspace, coding,
                governance, null, json, RuntimeOperationalTelemetry.noop(), null, null);
    }

    public RuntimeToolExecutionApplicationService(
            RuntimeApplicationApi runtime,
            RuntimeCapabilityCatalogApplicationApi catalog,
            ToolExecutionLedgerApplicationApi ledger,
            SandboxToolExecutionApplicationApi sandbox,
            ExternalRuntimeToolApplicationApi external,
            KnowledgeApplicationApi knowledge,
            WorkspaceToolApplicationApi workspace,
            CodingRuntimeApplicationApi coding,
            GovernanceApplicationApi governance,
            DocumentWorkspaceToolApplicationApi documentWorkspaces,
            ObjectMapper json,
            RuntimeOperationalTelemetry telemetry) {
        this(runtime, catalog, ledger, sandbox, external, knowledge,
                workspace, coding, governance, documentWorkspaces, json, telemetry, null, null);
    }

    @Autowired
    public RuntimeToolExecutionApplicationService(
            RuntimeApplicationApi runtime,
            RuntimeCapabilityCatalogApplicationApi catalog,
            ToolExecutionLedgerApplicationApi ledger,
            SandboxToolExecutionApplicationApi sandbox,
            ExternalRuntimeToolApplicationApi external,
            KnowledgeApplicationApi knowledge,
            WorkspaceToolApplicationApi workspace,
            CodingRuntimeApplicationApi coding,
            GovernanceApplicationApi governance,
            DocumentWorkspaceToolApplicationApi documentWorkspaces,
            ObjectMapper json,
            RuntimeOperationalTelemetry telemetry,
            AgentRunConfigurationSnapshotRepository runConfigurations,
            McpBindingValidationApplicationApi mcpValidation,
            com.spaceagent.platform.identity.api.IdentityExecutionAuthorizationApi actorAuthorization) {
        this.runtime = runtime;
        this.catalog = catalog;
        this.ledger = ledger;
        this.sandbox = sandbox;
        this.external = external;
        this.knowledge = knowledge;
        this.documentWorkspaces = documentWorkspaces;
        this.workspace = workspace;
        this.coding = coding;
        this.governance = governance;
        this.json = json;
        this.telemetry = telemetry;
        this.runConfigurations = runConfigurations;
        this.mcpValidation = mcpValidation;
        this.actorAuthorization = java.util.Objects.requireNonNull(actorAuthorization);
    }

    private final com.spaceagent.platform.identity.api.IdentityExecutionAuthorizationApi actorAuthorization;

    public RuntimeToolExecutionApplicationService(RuntimeApplicationApi runtime,
            RuntimeCapabilityCatalogApplicationApi catalog, ToolExecutionLedgerApplicationApi ledger,
            SandboxToolExecutionApplicationApi sandbox, ExternalRuntimeToolApplicationApi external,
            KnowledgeApplicationApi knowledge, WorkspaceToolApplicationApi workspace, CodingRuntimeApplicationApi coding,
            GovernanceApplicationApi governance, DocumentWorkspaceToolApplicationApi documentWorkspaces,
            ObjectMapper json, RuntimeOperationalTelemetry telemetry, AgentRunConfigurationSnapshotRepository runConfigurations,
            McpBindingValidationApplicationApi mcpValidation) {
        this(runtime, catalog, ledger, sandbox, external, knowledge, workspace, coding, governance,
                documentWorkspaces, json, telemetry, runConfigurations, mcpValidation,
                (t, u, w) -> { throw business("Actor admission is not configured", "EXECUTION_ACTOR_UNAVAILABLE", HttpStatus.FORBIDDEN); });
    }

    @Override
    public RuntimeToolResult execute(ExecuteRuntimeToolCommand command) {
        AgentRunView run = requireRun(command.userId(), command.agentRunId());
        actorAuthorization.requireActiveActor(run.tenantId(), command.userId(), true);
        ToolAgentConfiguration agent = configuration(run);
        RuntimeCapabilityCatalogApplicationApi.ValidatedToolArguments validated =
                catalog.validateArguments(command.toolId(), command.argumentsJson());
        RuntimeCapabilityCatalogApplicationApi.CapabilityView definition = catalog
                .findTool(validated.canonicalToolId()).orElseThrow();
        requireEnabled(agent, definition.id());
        try (RuntimeOperationalTelemetry.InvocationSpan span = telemetry.startTool(definition.id())) {
            try {
                RuntimeToolResult result = executeValidated(command, run, agent, validated, definition);
                if ("SUCCEEDED".equals(result.status())) span.success();
                else span.error("tool_failed");
                return result;
            } catch (RuntimeException error) {
                span.error(error instanceof BusinessException business
                        ? business.getCode() : "tool_execution_error");
                throw error;
            }
        }
    }

    @Autowired
    private ProjectChatWorkspaceBinding projectChatWorkspace;

    private RuntimeToolResult executeValidated(
            ExecuteRuntimeToolCommand command,
            AgentRunView run,
            ToolAgentConfiguration agent,
            RuntimeCapabilityCatalogApplicationApi.ValidatedToolArguments validated,
            RuntimeCapabilityCatalogApplicationApi.CapabilityView definition) {
        ToolMcpBinding pinnedMcpBinding = null;
        if (definition.requiresWorkspace() && projectChatWorkspace != null
                && projectChatWorkspace.find(run).isPresent()) {
            projectChatWorkspace.readScope(run, command.userId(), definition.id(),
                    string(validated.arguments(), "workspaceId"));
        }
        if ("mcp_call".equals(definition.id())) {
            pinnedMcpBinding = requirePinnedMcpBinding(agent, validated.arguments());
        }
        if (!"echo".equals(definition.id())
                && !definition.id().startsWith("coding_")
                && !definition.id().startsWith("document_workspace_")) {
            RuntimeToolResult replay = replayExisting(
                    command, definition.id(), validated.arguments());
            if (replay != null) return replay;
        }
        if (definition.requiresNetwork()) {
            if (!agent.networkEnabled()) {
                throw business(
                        "Agent network policy denies this Tool",
                        "TOOL_NETWORK_DISABLED", HttpStatus.FORBIDDEN);
            }
            authorize(run, command.userId(), GovernanceActionType.NETWORK_ACCESS,
                    "NETWORK_TOOL", definition.id(), validated.arguments());
        }
        if (definition.requiresWorkspace()) {
            if (projectChatWorkspace != null && projectChatWorkspace.find(run).isPresent()) {
                projectChatWorkspace.readScope(run, command.userId(), definition.id(),
                        string(validated.arguments(), "workspaceId"));
            } else {
                requireProjectRun(run);
                requireWorkspaceBinding(run, validated.arguments());
            }
        }
        if ("echo".equals(definition.id())) {
            return echo(command, validated.arguments());
        }
        if (definition.id().startsWith("coding_")) {
            return coding(command, run, definition.id(), validated.arguments());
        }
        if (definition.id().startsWith("document_workspace_")) {
            return documentWorkspace(command, run, definition.id(), validated.arguments());
        }

        boolean readOnly = definition.readOnly();
        Boolean expectedMcpReadOnly = null;
        if ("mcp_call".equals(definition.id())) {
            requireCurrentMcpBinding(run, command.userId(), pinnedMcpBinding);
            var descriptor = external.describeMcpTool(
                    run.tenantId(), command.userId(),
                    string(validated.arguments(), "connectionId"),
                    string(validated.arguments(), "remoteTool"));
            readOnly = descriptor.readOnly();
            expectedMcpReadOnly = readOnly;
        }
        if ("document_write".equals(definition.id())) {
            authorize(run, command.userId(), GovernanceActionType.CODING_FILE_MUTATION,
                    "WORKSPACE", string(validated.arguments(), "workspaceId"),
                    validated.arguments());
        }
        return ledgered(
                command, run, agent, definition.id(), validated.arguments(),
                readOnly, expectedMcpReadOnly);
    }

    private ToolMcpBinding requirePinnedMcpBinding(
            ToolAgentConfiguration agent,
            Map<String, Object> arguments) {
        String connectionId = string(arguments, "connectionId");
        String remoteTool = string(arguments, "remoteTool");
        List<ToolMcpBinding> matches = agent.mcpBindings().stream()
                .filter(binding -> connectionId.equals(binding.connectionId()))
                .filter(binding -> binding.allowedToolNames().contains(remoteTool))
                .toList();
        if (matches.size() != 1) {
            throw business(
                    "Run configuration does not authorize this MCP Tool",
                    "RUN_CONFIGURATION_MCP_TOOL_NOT_BOUND", HttpStatus.FORBIDDEN);
        }
        return matches.getFirst();
    }

    private void requireCurrentMcpBinding(
            AgentRunView run,
            String userId,
            ToolMcpBinding pinned) {
        if (mcpValidation != null) {
            mcpValidation.validate(new McpBindingValidationApplicationApi.ValidateCommand(
                    run.tenantId(), userId, pinned.installationId(), pinned.connectionId(),
                    pinned.serverVersionId(), pinned.capabilitySnapshotId(),
                    pinned.connectionRevision(), pinned.snapshotSha256(),
                    pinned.allowedToolNames()));
            return;
        }
        throw business("MCP binding validation is unavailable",
                "MCP_BINDING_VALIDATION_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
    }

    private RuntimeToolResult ledgered(
            ExecuteRuntimeToolCommand command,
            AgentRunView run,
            ToolAgentConfiguration agent,
            String toolId,
            Map<String, Object> arguments,
            boolean readOnly,
            Boolean expectedMcpReadOnly) {
        String argumentsJson = writeCanonical(arguments);
        String inputHash = "sha256:" + hash(argumentsJson);
        var claim = ledger.claim(new ClaimToolExecutionCommand(
                run.id(), command.runStepId(), toolId, command.toolCallId(),
                run.id() + ":" + command.toolCallId(), argumentsJson, inputHash, 150));
        if (claim.type() == ToolExecutionClaimDecisionType.REPLAY) {
            return view(claim.ledger());
        }
        if (claim.type() == ToolExecutionClaimDecisionType.BUSY) {
            throw business("Tool execution is in progress", "TOOL_EXECUTION_IN_PROGRESS",
                    HttpStatus.CONFLICT);
        }
        if (claim.type() == ToolExecutionClaimDecisionType.UNKNOWN) {
            throw business("Tool outcome is unknown", "TOOL_EXECUTION_AMBIGUOUS",
                    HttpStatus.CONFLICT);
        }
        if (claim.type() == ToolExecutionClaimDecisionType.CONFLICT) {
            throw business("Tool idempotency conflict", "TOOL_IDEMPOTENCY_CONFLICT",
                    HttpStatus.CONFLICT);
        }
        try {
            ToolOutcome outcome = invoke(
                    run, command.userId(), agent, toolId, arguments, expectedMcpReadOnly);
            ToolExecutionStatus status = outcome.failed()
                    ? ToolExecutionStatus.FAILED : ToolExecutionStatus.SUCCEEDED;
            var transition = ledger.complete(new CompleteToolExecutionCommand(
                    run.id(), command.toolCallId(), claim.claimToken(), claim.revision(),
                    status, bound(outcome.content()), outcome.resultRef(),
                    outcome.failed() ? outcome.errorCode() : null));
            return view(transition.ledger());
        } catch (WorkspaceSandboxExecutionException error) {
            if (!readOnly && error.ambiguous()) {
                var transition = ledger.markUnknown(new MarkToolExecutionUnknownCommand(
                        run.id(), command.toolCallId(), claim.claimToken(), claim.revision(),
                        error.safeCode()));
                return view(transition.ledger());
            }
            var transition = ledger.complete(new CompleteToolExecutionCommand(
                    run.id(), command.toolCallId(), claim.claimToken(), claim.revision(),
                    ToolExecutionStatus.FAILED, null, null, error.safeCode()));
            return view(transition.ledger());
        } catch (RuntimeException error) {
            if (readOnly) {
                try {
                    ledger.complete(new CompleteToolExecutionCommand(
                            run.id(), command.toolCallId(), claim.claimToken(), claim.revision(),
                            ToolExecutionStatus.FAILED, null, null, "TOOL_READ_FAILED"));
                } catch (RuntimeException ignored) {
                    // Existing terminal/unknown evidence remains authoritative.
                }
            } else {
                try {
                    ledger.markUnknown(new MarkToolExecutionUnknownCommand(
                            run.id(), command.toolCallId(), claim.claimToken(), claim.revision(),
                            "tool side-effect outcome unknown"));
                } catch (RuntimeException ignored) {
                    // Existing terminal/unknown evidence remains authoritative.
                }
            }
            if (error instanceof BusinessException business) throw business;
            throw business(
                    readOnly ? "Tool read failed" : "Tool outcome is unknown",
                    readOnly ? "TOOL_READ_FAILED" : "TOOL_EXECUTION_AMBIGUOUS",
                    readOnly ? HttpStatus.BAD_GATEWAY : HttpStatus.CONFLICT);
        }
    }

    private ToolOutcome invoke(
            AgentRunView run,
            String userId,
            ToolAgentConfiguration agent,
            String toolId,
            Map<String, Object> arguments,
            Boolean expectedMcpReadOnly) {
        return switch (toolId) {
            case "knowledge_search" -> {
                if (!agent.ragEnabled() || agent.knowledgeBaseIds().isEmpty() && agent.knowledgeCollectionIds().isEmpty()) {
                    throw business(
                            "Run configuration has no enabled Knowledge bindings",
                            "TOOL_KNOWLEDGE_NOT_BOUND", HttpStatus.CONFLICT);
                }
                if(!agent.knowledgeCollectionIds().isEmpty()) {
                    if(collectionRetrieval==null)throw business("Knowledge retrieval unavailable","KNOWLEDGE_VECTOR_UNAVAILABLE",HttpStatus.SERVICE_UNAVAILABLE);
                    String query=string(arguments,"query");
                    String key=java.util.UUID.nameUUIDFromBytes((run.id()+":"+query).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
                    yield new ToolOutcome(write(collectionRetrieval.retrieve(new com.spaceagent.platform.knowledge.api.KnowledgeCollectionRetrievalApi.Query(
                            new com.spaceagent.platform.knowledge.api.KnowledgeBaseApplicationApi.Actor(userId,run.tenantId()),agent.knowledgeCollectionIds(),query,
                            integer(arguments,"topK",5),2048,key))),null,false,null);
                }
                yield new ToolOutcome(write(knowledge.retrieve(
                        new KnowledgeRetrievalCommand(
                                userId, agent.knowledgeBaseIds(),
                                string(arguments, "query"),
                                integer(arguments, "topK", 5)))), null, false, null);
            }
            case "file_read" -> new ToolOutcome(write(workspace.readFile(
                    new WorkspaceToolApplicationApi.FileReadQuery(
                            scope(run, userId, arguments), string(arguments, "path"),
                            integer(arguments, "maxCharacters", 50_000)))), null, false, null);
            case "file_list" -> new ToolOutcome(write(workspace.listFiles(
                    new WorkspaceToolApplicationApi.FileListQuery(
                            scope(run, userId, arguments), optional(arguments, "path"),
                            integer(arguments, "maxDepth", 2),
                            integer(arguments, "maxEntries", 200)))), null, false, null);
            case "git_status" -> {
                var value = workspace.git(new WorkspaceToolApplicationApi.GitQuery(
                        scope(run, userId, arguments), 1_000));
                yield new ToolOutcome(write(Map.of(
                        "headCommit", value.headCommit(), "status", value.status(),
                        "changedFiles", value.changedFiles())), null, false, null);
            }
            case "git_diff" -> {
                var value = workspace.git(new WorkspaceToolApplicationApi.GitQuery(
                        scope(run, userId, arguments),
                        integer(arguments, "maxCharacters", 100_000)));
                yield new ToolOutcome(write(Map.of(
                        "headCommit", value.headCommit(), "patch", value.patch(),
                        "changedFiles", value.changedFiles(), "truncated", value.truncated())),
                        null, false, null);
            }
            case "document_read" -> new ToolOutcome(write(workspace.readDocument(
                    new WorkspaceToolApplicationApi.DocumentReadQuery(
                            scope(run, userId, arguments), string(arguments, "path"),
                            integer(arguments, "maxCharacters", 100_000)))), null, false, null);
            case "document_write" -> new ToolOutcome(write(workspace.writeDocument(
                    new WorkspaceToolApplicationApi.DocumentWriteCommand(
                            scope(run, userId, arguments), string(arguments, "path"),
                            string(arguments, "content"), string(arguments, "format")))),
                    null, false, null);
            case "web_search", "http_fetch", "mcp_call",
                    "github_search_repositories", "github_get_repository" -> {
                var value = external.execute(new ExternalRuntimeToolApplicationApi.ExecuteCommand(
                        run.tenantId(), userId, toolId, arguments, expectedMcpReadOnly));
                yield new ToolOutcome(
                        value.content(), null, value.remoteError(),
                        value.remoteError() ? "TOOL_REMOTE_REJECTED" : null);
            }
            default -> throw new IllegalArgumentException("Unsupported Runtime Tool");
        };
    }

    private RuntimeToolResult echo(
            ExecuteRuntimeToolCommand command, Map<String, Object> arguments) {
        var result = sandbox.execute(new SandboxToolExecutionCommand(
                command.agentRunId(), command.runStepId(), "echo", command.toolCallId(),
                command.agentRunId() + ":" + command.toolCallId(), "echo",
                List.of(string(arguments, "text")), null, null, 30));
        return new RuntimeToolResult(
                command.toolCallId(), "echo", result.status(),
                result.result(), result.resultRef(), result.error());
    }

    private RuntimeToolResult documentWorkspace(ExecuteRuntimeToolCommand command,AgentRunView run,
            String toolId,Map<String,Object> arguments){
        if(documentWorkspaces==null)throw business("Document Workspace tools are unavailable","TOOL_UNAVAILABLE",HttpStatus.SERVICE_UNAVAILABLE);
        var scope=new DocumentWorkspaceToolApplicationApi.Scope(run.tenantId(),command.userId(),
                string(arguments,"workspaceId"),run.id(),command.runStepId(),command.toolCallId(),
                run.id()+":"+command.toolCallId());
        Object value=switch(toolId){
            case "document_workspace_read"->documentWorkspaces.read(new DocumentWorkspaceToolApplicationApi.ReadQuery(scope,string(arguments,"path"),integer(arguments,"maxCharacters",100_000)));
            case "document_workspace_list"->documentWorkspaces.list(new DocumentWorkspaceToolApplicationApi.ListQuery(scope,optional(arguments,"path"),integer(arguments,"maxDepth",2),integer(arguments,"maxEntries",200)));
            case "document_workspace_write"->documentWorkspaces.write(new DocumentWorkspaceToolApplicationApi.WriteCommand(scope,string(arguments,"path"),string(arguments,"content"),optional(arguments,"approvalId")));
            case "document_workspace_delete"->documentWorkspaces.delete(new DocumentWorkspaceToolApplicationApi.DeleteCommand(scope,string(arguments,"path"),optional(arguments,"approvalId")));
            default->throw new IllegalArgumentException("Unsupported Document Workspace Tool");};
        return new RuntimeToolResult(command.toolCallId(),toolId,"SUCCEEDED",write(value),null,null);
    }

    private RuntimeToolResult coding(
            ExecuteRuntimeToolCommand command,
            AgentRunView run,
            String toolId,
            Map<String, Object> arguments) {
        WorkspaceCodingGateway.Type type = switch (toolId) {
            case "coding_write_file" -> WorkspaceCodingGateway.Type.WRITE_FILE;
            case "coding_delete_file" -> WorkspaceCodingGateway.Type.DELETE_FILE;
            case "coding_run_command" -> WorkspaceCodingGateway.Type.RUN_COMMAND;
            default -> throw new IllegalArgumentException("Unsupported Coding Tool");
        };
        var result = coding.execute(new CodingRuntimeApplicationApi.ActionCommand(
                command.userId(), run.id(), string(arguments, "workspaceId"),
                command.toolCallId(), type, optional(arguments, "path"),
                optional(arguments, "content"), optional(arguments, "executable"),
                strings(arguments.get("arguments")),
                integer(arguments, "timeoutSeconds", 120),
                optional(arguments, "approvalId")));
        return new RuntimeToolResult(
                command.toolCallId(), toolId, result.status(),
                bound(result.output()), null,
                "FAILED".equals(result.status()) ? "CODING_TOOL_FAILED" : null);
    }

    private void authorize(
            AgentRunView run,
            String userId,
            GovernanceActionType type,
            String resourceType,
            String resourceId,
            Map<String, Object> arguments) {
        Map<String, Object> operation = new LinkedHashMap<>(arguments);
        String approvalId = optional(operation, "approvalId");
        operation.remove("approvalId");
        var authorization = governance.authorize(new GovernanceApplicationApi.AuthorizeCommand(
                run.tenantId(), userId, type, resourceType, resourceId,
                "sha256:" + hash(writeCanonical(Map.of(
                        "runId", run.id(), "tool", resourceId, "arguments", operation))),
                "Runtime Tool " + resourceId, approvalId));
        if (authorization.status()
                == GovernanceApplicationApi.AuthorizationStatus.APPROVAL_REQUIRED) {
            String id = authorization.approval() == null
                    ? "unknown" : authorization.approval().id();
            throw new GovernanceApprovalRequiredException(id);
        }
        if (!authorization.allowed()) {
            throw business(
                    "Approval is invalid or does not match this Tool operation",
                    "GOVERNANCE_APPROVAL_INVALID", HttpStatus.CONFLICT);
        }
    }

    private WorkspaceToolApplicationApi.Scope scope(
            AgentRunView run, String userId, Map<String, Object> arguments) {
        if (projectChatWorkspace != null && projectChatWorkspace.find(run).isPresent()) {
            var pinned = projectChatWorkspace.readScope(run, userId, "file_read", string(arguments, "workspaceId"));
            return new WorkspaceToolApplicationApi.Scope(pinned.tenantId(), userId, pinned.projectId(),
                    pinned.taskId(), pinned.workspaceId(), run.id(),
                    "workspace-tool:" + hash(writeCanonical(arguments)).substring(0, 24));
        }
        requireProjectRun(run);
        String workspaceId = string(arguments, "workspaceId");
        requireWorkspaceBinding(run, arguments);
        return new WorkspaceToolApplicationApi.Scope(
                run.tenantId(), userId, run.projectId(), run.taskId(),
                workspaceId, run.id(),
                "workspace-tool:" + hash(writeCanonical(arguments)).substring(0, 24));
    }

    private static void requireWorkspaceBinding(
            AgentRunView run, Map<String, Object> arguments) {
        String workspaceId = string(arguments, "workspaceId");
        if (run.workspaceId() != null && !run.workspaceId().equals(workspaceId)) {
            throw business(
                    "Runtime Tool Workspace does not match the Run binding",
                    "TOOL_WORKSPACE_BINDING_MISMATCH", HttpStatus.CONFLICT);
        }
    }

    private ToolAgentConfiguration configuration(AgentRunView run) {
        AgentRunConfigurationSnapshot snapshot = runConfigurations.findByRunId(
                            run.tenantId(), run.ownerId(), run.id())
                    .orElseThrow(() -> business(
                            "Run Agent configuration snapshot is missing",
                            "RUN_AGENT_CONFIGURATION_MISSING", HttpStatus.CONFLICT));
            if (snapshot.state() != AgentRunConfigurationSnapshot.State.SNAPSHOTTED) {
                throw business(
                        "Historical Run has no trustworthy Agent configuration snapshot",
                        "RUN_AGENT_CONFIGURATION_UNAVAILABLE", HttpStatus.CONFLICT);
            }
        return new ToolAgentConfiguration(
                    snapshot.enabledToolIds(), snapshot.ragEnabled(), snapshot.networkEnabled(),
                    snapshot.knowledgeBaseIds(), snapshot.mcpBindings().stream()
                            .map(binding -> new ToolMcpBinding(
                                    binding.sourceBindingId(), binding.installationId(),
                                    binding.connectionId(), binding.serverVersionId(),
                                    binding.capabilitySnapshotId(), binding.connectionRevision(),
                                    binding.snapshotSha256(), binding.allowedToolNames(),
                                    binding.bindingSha256()))
                            .toList(),snapshot.knowledgeCollectionIds());
    }

    private void requireEnabled(
            ToolAgentConfiguration agent, String toolId) {
        LinkedHashSet<String> enabled = new LinkedHashSet<>();
        for (var tool : catalog.resolveTools(agent.enabledToolIds())) enabled.add(tool.id());
        if (!enabled.contains(toolId)) {
            throw business(
                    "Runtime Tool is not enabled on the Run configuration",
                    "TOOL_NOT_ENABLED", HttpStatus.FORBIDDEN);
        }
    }

    private static void requireProjectRun(AgentRunView run) {
        if (run.tenantId() == null || run.projectId() == null || run.taskId() == null) {
            throw business(
                    "Runtime Tool requires a Project Task-scoped Run",
                    "TOOL_PROJECT_SCOPE_REQUIRED", HttpStatus.CONFLICT);
        }
    }

    private AgentRunView requireRun(String userId, String runId) {
        return runtime.findRun(runId)
                .filter(run -> run.ownerId().equals(userId))
                .orElseThrow(() -> business(
                        "AgentRun not found", "TOOL_RUN_NOT_FOUND", HttpStatus.NOT_FOUND));
    }

    private RuntimeToolResult view(ToolExecutionLedgerView value) {
        return new RuntimeToolResult(
                value.toolCallId(), value.toolName(), value.status().name(),
                value.result(), value.resultRef(), value.error(), value.revision());
    }

    private RuntimeToolResult replayExisting(
            ExecuteRuntimeToolCommand command,
            String toolId,
            Map<String, Object> arguments) {
        String argumentsJson = writeCanonical(arguments);
        String inputHash = "sha256:" + hash(argumentsJson);
        ToolExecutionLedgerView existing = ledger.findByRunId(command.agentRunId()).stream()
                .filter(value -> value.toolCallId().equals(command.toolCallId()))
                .findFirst().orElse(null);
        if (existing == null) return null;
        if (!toolId.equals(existing.toolName()) || !inputHash.equals(existing.inputHash())) {
            throw business(
                    "Tool idempotency conflict", "TOOL_IDEMPOTENCY_CONFLICT",
                    HttpStatus.CONFLICT);
        }
        if (isTerminal(existing.status())) return view(existing);
        if (existing.status() == ToolExecutionStatus.UNKNOWN) {
            throw business(
                    "Tool outcome is unknown", "TOOL_EXECUTION_AMBIGUOUS",
                    HttpStatus.CONFLICT);
        }
        var decision = ledger.claim(new ClaimToolExecutionCommand(
                command.agentRunId(), command.runStepId(), toolId,
                command.toolCallId(), command.agentRunId() + ":" + command.toolCallId(),
                argumentsJson, inputHash, 150));
        if (decision.type() == ToolExecutionClaimDecisionType.REPLAY) {
            return view(decision.ledger());
        }
        if (decision.type() == ToolExecutionClaimDecisionType.UNKNOWN) {
            throw business(
                    "Tool outcome is unknown", "TOOL_EXECUTION_AMBIGUOUS",
                    HttpStatus.CONFLICT);
        }
        if (decision.type() == ToolExecutionClaimDecisionType.CONFLICT) {
            throw business(
                    "Tool idempotency conflict", "TOOL_IDEMPOTENCY_CONFLICT",
                    HttpStatus.CONFLICT);
        }
        throw business(
                "Tool execution is in progress", "TOOL_EXECUTION_IN_PROGRESS",
                HttpStatus.CONFLICT);
    }

    private static boolean isTerminal(ToolExecutionStatus status) {
        return status == ToolExecutionStatus.SUCCEEDED
                || status == ToolExecutionStatus.FAILED
                || status == ToolExecutionStatus.TIMED_OUT
                || status == ToolExecutionStatus.CANCELLED;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Runtime Tool serialization failed");
        }
    }

    private String writeCanonical(Object value) {
        return write(canonical(value));
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> input) {
            Map<String, Object> result = new TreeMap<>();
            input.forEach((key, item) -> {
                if (key != null) result.put(String.valueOf(key), canonical(item));
            });
            return result;
        }
        if (value instanceof Iterable<?> input) {
            List<Object> result = new ArrayList<>();
            input.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return value;
    }

    private static String string(Map<String, Object> arguments, String key) {
        String value = optional(arguments, key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static String optional(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

    private static int integer(Map<String, Object> arguments, String key, int fallback) {
        Object value = arguments.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof Iterable<?> input)) return List.of();
        List<String> result = new ArrayList<>();
        input.forEach(item -> result.add(String.valueOf(item)));
        return List.copyOf(result);
    }

    private static String bound(String value) {
        String normalized = value == null ? "" : value;
        return normalized.substring(0, Math.min(normalized.length(), MAX_RESULT_CHARACTERS));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static BusinessException business(
            String message, String code, HttpStatus status) {
        return new BusinessException(message, status, code);
    }

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.spaceagent.platform.knowledge.api.KnowledgeCollectionRetrievalApi collectionRetrieval;

    private record ToolOutcome(
            String content, String resultRef, boolean failed, String errorCode) {
    }

    private record ToolAgentConfiguration(
            List<String> enabledToolIds,
            boolean ragEnabled,
            boolean networkEnabled,
            List<String> knowledgeBaseIds,
            List<ToolMcpBinding> mcpBindings,List<String> knowledgeCollectionIds) {
    }

    private record ToolMcpBinding(
            String id,
            String installationId,
            String connectionId,
            String serverVersionId,
            String capabilitySnapshotId,
            long connectionRevision,
            String snapshotSha256,
            List<String> allowedToolNames,
            String bindingSha256) {

    }
}
