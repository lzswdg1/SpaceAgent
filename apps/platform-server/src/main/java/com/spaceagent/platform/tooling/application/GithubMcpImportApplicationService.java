package com.spaceagent.platform.tooling.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.api.GithubMcpImportApplicationApi;
import com.spaceagent.platform.tooling.api.McpRemoteToolApplicationApi;
import com.spaceagent.platform.tooling.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
public class GithubMcpImportApplicationService implements GithubMcpImportApplicationApi {
    private static final long CLAIM_LEASE_SECONDS = 90;
    private final McpMarketplaceRepository marketplace;
    private final McpInvocationLedgerRepository ledger;
    private final McpRemoteToolApplicationApi tools;
    private final GithubOfficialMcpAdapter official;
    private final ObjectMapper json;
    private final IdGenerator ids;

    public GithubMcpImportApplicationService(
            McpMarketplaceRepository marketplace,
            McpInvocationLedgerRepository ledger,
            McpRemoteToolApplicationApi tools,
            GithubOfficialMcpAdapter official,
            ObjectMapper json,
            IdGenerator ids) {
        this.marketplace = marketplace;
        this.ledger = ledger;
        this.tools = tools;
        this.official = official;
        this.json = json;
        this.ids = ids;
    }

    @Override
    public ImportView resolve(ImportCommand command) {
        boolean publicUrl = command.githubUrl() != null && !command.githubUrl().isBlank();
        McpConnection connection = requireGithubConnection(
                command.tenantId(), command.userId(), command.connectionId(), publicUrl);
        Selection selection = selection(
                command.providerRepositoryId(), command.githubUrl(),
                GithubMcpProfiles.isOfficialRemote(connection));
        String idempotencyHash = hash(idempotencyKey(command.idempotencyKey()));
        String operationKey = operationKey(command.projectId());
        Map<String, Object> arguments = selection.arguments();
        String argumentsJson = write(arguments);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("tenantId", command.tenantId());
        input.put("userId", command.userId());
        input.put("projectId", command.projectId());
        input.put("connectionId", connection.id());
        input.put("toolName", selection.toolName());
        input.put("arguments", arguments);
        String inputHash = hash(write(input));
        String claimToken = UUID.randomUUID().toString();
        McpInvocationLedgerRepository.ClaimDecision claim = ledger.claim(
                new McpInvocationLedgerRepository.ClaimRequest(
                        ids.nextId(), command.tenantId(), command.userId(), connection.id(),
                        operationKey, idempotencyHash, selection.toolName(), argumentsJson,
                        inputHash, claimToken, "platform:" + UUID.randomUUID(), CLAIM_LEASE_SECONDS));
        switch (claim.type()) {
            case REPLAY -> {
                return replay(claim.ledger());
            }
            case BUSY -> throw conflict("GitHub MCP import is already running", "MCP_IMPORT_IN_PROGRESS");
            case UNKNOWN -> throw conflict(
                    "GitHub MCP import outcome is unknown; use a reviewed new operation",
                    "MCP_IMPORT_OUTCOME_UNKNOWN");
            case CONFLICT -> throw conflict(
                    "Idempotency key was already used with different import input",
                    "MCP_IMPORT_IDEMPOTENCY_CONFLICT");
            case CLAIMED -> {
                // The short durable claim transaction committed before the remote call.
            }
        }

        ImportView result;
        try {
            if (GithubMcpProfiles.isOfficialRemote(connection)) {
                String owner = String.valueOf(arguments.get("owner"));
                String name = String.valueOf(arguments.get("name"));
                result = repository(
                        claim.ledger().id(), connection.id(),
                        official.repository(command.tenantId(), command.userId(),
                                connection, owner, name));
            } else {
                McpRemoteToolApplicationApi.ToolResult remote = tools.callTool(
                        new McpRemoteToolApplicationApi.CallCommand(
                                command.tenantId(), command.userId(), connection.id(),
                                selection.toolName(), arguments));
                if (remote.error()) {
                    McpInvocationLedgerRepository.Transition failed = ledger.complete(
                            new McpInvocationLedgerRepository.CompleteRequest(
                                    claim.ledger().id(), claimToken, claim.ledger().revision(),
                                    McpInvocationStatus.FAILED, null, "MCP_REMOTE_REJECTED"));
                    terminal(failed);
                    throw new BusinessException(
                            "GitHub MCP rejected repository import", HttpStatus.BAD_GATEWAY,
                            "MCP_IMPORT_REMOTE_FAILED");
                }
                result = repository(
                        claim.ledger().id(), connection.id(), remote.structuredContent());
            }
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            try {
                ledger.markUnknown(new McpInvocationLedgerRepository.UnknownRequest(
                        claim.ledger().id(), claimToken, claim.ledger().revision(),
                        "MCP_REMOTE_OUTCOME_UNKNOWN"));
            } catch (RuntimeException ignored) {
                // A concurrent terminal result will be observed on replay.
            }
            throw conflict(
                    "GitHub MCP import outcome is unknown; use a reviewed new operation",
                    "MCP_IMPORT_OUTCOME_UNKNOWN");
        }
        McpInvocationLedgerRepository.Transition completed = ledger.complete(
                new McpInvocationLedgerRepository.CompleteRequest(
                        claim.ledger().id(), claimToken, claim.ledger().revision(),
                        McpInvocationStatus.SUCCEEDED, write(result), null));
        return replay(terminal(completed));
    }

    private McpConnection requireGithubConnection(
            String tenantId, String userId, String connectionId, boolean publicUrl) {
        McpConnection connection = marketplace.findConnections(tenantId, userId).stream()
                .filter(value -> value.id().equals(connectionId))
                .findFirst().orElseThrow(() -> notFound());
        McpInstallation installation = marketplace.findInstallation(connection.installationId())
                .orElseThrow(GithubMcpImportApplicationService::notFound);
        marketplace.findEntry(installation.entryId())
                .filter(value -> value.slug().equals("github"))
                .orElseThrow(GithubMcpImportApplicationService::notFound);
        boolean available = connection.state() == McpConnectionState.ACTIVE
                || (publicUrl && !GithubMcpProfiles.isOfficialRemote(connection)
                && connection.state() == McpConnectionState.PENDING_AUTH);
        if (!available) {
            throw conflict("GitHub MCP connection is not available", "MCP_IMPORT_CONNECTION_STATE");
        }
        return connection;
    }

    private ImportView replay(McpInvocationLedger value) {
        if (value.status() == McpInvocationStatus.FAILED) {
            throw new BusinessException(
                    "GitHub MCP rejected repository import", HttpStatus.BAD_GATEWAY,
                    "MCP_IMPORT_REMOTE_FAILED");
        }
        if (value.status() != McpInvocationStatus.SUCCEEDED || value.resultJson() == null) {
            throw conflict("GitHub MCP import outcome is unavailable", "MCP_IMPORT_OUTCOME_UNKNOWN");
        }
        try {
            return json.readValue(value.resultJson(), ImportView.class);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to read MCP import evidence", error);
        }
    }

    private McpInvocationLedger terminal(McpInvocationLedgerRepository.Transition transition) {
        return switch (transition.type()) {
            case APPLIED, CURRENT_TERMINAL -> transition.ledger();
            case CURRENT_UNKNOWN -> throw conflict(
                    "GitHub MCP import outcome is unknown; use a reviewed new operation",
                    "MCP_IMPORT_OUTCOME_UNKNOWN");
            case CLAIM_LOST -> throw conflict(
                    "GitHub MCP import claim was lost", "MCP_IMPORT_CLAIM_LOST");
        };
    }

    private ImportView repository(String invocationId, String connectionId, Object value) {
        if (value == null) throw new IllegalStateException("GitHub MCP returned no repository");
        Map<String, Object> data = json.convertValue(value, new TypeReference<Map<String, Object>>() {});
        String owner = identifier(text(data, "owner"), "owner");
        String name = identifier(text(data, "name"), "name");
        String html = repositoryUrl(text(data, "htmlUrl"), owner, name, false);
        String clone = repositoryUrl(text(data, "cloneUrl"), owner, name, true);
        String branch = branch(text(data, "defaultBranch"));
        return new ImportView(
                invocationId, connectionId, text(data, "id"), owner, name, html, clone, branch,
                Boolean.TRUE.equals(data.get("private")), Boolean.TRUE.equals(data.get("archived")));
    }

    private ImportView repository(
            String invocationId,
            String connectionId,
            com.spaceagent.platform.tooling.api.GithubMcpApplicationApi.RepositoryView value) {
        return new ImportView(
                invocationId, connectionId, value.providerRepositoryId(), value.owner(),
                value.name(), value.htmlUrl(), value.cloneUrl(), value.defaultBranch(),
                value.privateRepository(), value.archived());
    }

    private Selection selection(
            String providerRepositoryId, String githubUrl, boolean officialProfile) {
        boolean id = providerRepositoryId != null && !providerRepositoryId.isBlank();
        boolean url = githubUrl != null && !githubUrl.isBlank();
        if (id == url) {
            throw invalid("Exactly one providerRepositoryId or githubUrl is required");
        }
        if (id) {
            String normalized = providerRepositoryId.trim();
            if (normalized.length() > 180 || !normalized.matches("[A-Za-z0-9_.:/-]+")) {
                throw invalid("providerRepositoryId is invalid");
            }
            if (officialProfile) {
                String[] parts = normalized.split("/", -1);
                if (parts.length != 2) throw invalid("providerRepositoryId is invalid");
                return new Selection(
                        "search_repositories",
                        Map.of("owner", identifier(parts[0], "owner"),
                                "name", identifier(parts[1], "name")), false);
            }
            return new Selection("github_get_repository", Map.of("repositoryId", normalized), false);
        }
        if (officialProfile) {
            URI uri = URI.create(strictGithubUrl(githubUrl));
            String[] parts = uri.getPath().substring(1).split("/", -1);
            return new Selection(
                    "search_repositories",
                    Map.of("owner", parts[0], "name", parts[1]), true);
        }
        return new Selection(
                "github_resolve_repository", Map.of("url", strictGithubUrl(githubUrl)), true);
    }

    private static String operationKey(String projectId) {
        try {
            return "github-project-import:" + UUID.fromString(projectId);
        } catch (Exception error) {
            throw invalid("projectId is invalid");
        }
    }

    private static String idempotencyKey(String value) {
        String key = value == null ? "" : value.trim();
        if (key.isEmpty() || key.length() > 160 || !key.matches("[A-Za-z0-9._:-]+")) {
            throw invalid("Idempotency-Key is invalid");
        }
        return key;
    }

    private static String strictGithubUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"github.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            String[] parts = uri.getPath().replaceAll("^/+|/+$", "").split("/");
            if (parts.length != 2) throw new IllegalArgumentException();
            String owner = identifier(parts[0], "owner");
            String rawName = parts[1].endsWith(".git")
                    ? parts[1].substring(0, parts[1].length() - 4) : parts[1];
            String name = identifier(rawName, "name");
            return "https://github.com/" + owner + "/" + name;
        } catch (RuntimeException error) {
            throw invalid("githubUrl must identify a GitHub repository");
        }
    }

    private static String repositoryUrl(
            String value, String owner, String name, boolean clone) {
        String expected = "https://github.com/" + owner + "/" + name + (clone ? ".git" : "");
        if (!expected.equalsIgnoreCase(value)) {
            throw new IllegalStateException("GitHub MCP returned an invalid repository URL");
        }
        return expected;
    }

    private static String identifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 100
                || !normalized.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalStateException("GitHub MCP returned an invalid " + field);
        }
        return normalized;
    }

    private static String branch(String value) {
        String branch = value == null ? "" : value.trim();
        if (branch.isEmpty() || branch.length() > 255 || branch.contains("..")
                || branch.contains(" ") || branch.contains("~") || branch.contains("^")
                || branch.contains(":")) {
            throw new IllegalStateException("GitHub MCP returned an invalid default branch");
        }
        return branch;
    }

    private static String text(Map<String, Object> value, String field) {
        Object raw = value.get(field);
        String text = raw == null ? "" : String.valueOf(raw).trim();
        if (text.isEmpty() || text.length() > 1000) {
            throw new IllegalStateException("GitHub MCP response missing " + field);
        }
        return text;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize MCP invocation", error);
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(message, HttpStatus.BAD_REQUEST, "MCP_IMPORT_INPUT_INVALID");
    }

    private static BusinessException notFound() {
        return new BusinessException(
                "GitHub MCP connection not found", HttpStatus.NOT_FOUND, "MCP_IMPORT_NOT_FOUND");
    }

    private static BusinessException conflict(String message, String code) {
        return new BusinessException(message, HttpStatus.CONFLICT, code);
    }

    private record Selection(String toolName, Map<String, Object> arguments, boolean publicUrl) {
    }
}
