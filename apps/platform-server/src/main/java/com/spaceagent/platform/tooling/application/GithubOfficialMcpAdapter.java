package com.spaceagent.platform.tooling.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.api.GithubMcpApplicationApi.RepositoryView;
import com.spaceagent.platform.tooling.api.McpRemoteToolApplicationApi;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpRemoteToolGateway;
import com.spaceagent.shared.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class GithubOfficialMcpAdapter {
    private static final int MAX_REPOSITORIES = 100;
    private static final int MAX_REMOTE_ERROR_CHARACTERS = 4_096;
    private static final Logger log = LoggerFactory.getLogger(GithubOfficialMcpAdapter.class);
    private final McpRemoteToolApplicationApi tools;
    private final McpRemoteToolGateway gateway;
    private final ObjectMapper json;

    public GithubOfficialMcpAdapter(
            McpRemoteToolApplicationApi tools,
            McpRemoteToolGateway gateway,
            ObjectMapper json) {
        this.tools = tools;
        this.gateway = gateway;
        this.json = json;
    }

    public Account account(McpConnection connection, Map<String, String> auth) {
        McpRemoteToolGateway.RemoteResult result = gateway.callTool(
                connection, auth, "get_me", Map.of());
        if (result.error()) throw new IllegalStateException("GitHub MCP get_me failed");
        JsonNode root = json(result.structuredContent(), result.text());
        return new Account(text(root, "id", 160), identifier(text(root, "login", 100), "login"));
    }

    public List<RepositoryView> repositories(
            String tenantId, String userId, McpConnection connection) {
        String login = identifier(connection.externalAccountName(), "login");
        LinkedHashMap<String, RepositoryView> repositories = new LinkedHashMap<>();
        RuntimeException failure = null;
        boolean successfulSearch = false;
        try {
            merge(repositories, search(tenantId, userId, connection.id(),
                    "user:" + login, 100));
            successfulSearch = true;
        } catch (RuntimeException error) {
            failure = error;
            log.warn("GitHub MCP owner repository search was unavailable: code={}",
                    safeCode(error));
        }
        if (repositories.size() < MAX_REPOSITORIES) {
            try {
                merge(repositories, search(tenantId, userId, connection.id(),
                        "is:private", 100));
                successfulSearch = true;
            } catch (RuntimeException error) {
                failure = preferredFailure(failure, error);
                log.warn("GitHub MCP private repository search was unavailable: code={}",
                        safeCode(error));
            }
        }
        if (!successfulSearch) {
            throw failure == null
                    ? failure(HttpStatus.BAD_GATEWAY,
                            "GITHUB_MCP_REPOSITORY_UPSTREAM_UNAVAILABLE",
                            "GitHub MCP repository service is unavailable")
                    : failure;
        }
        return repositories.values().stream().limit(MAX_REPOSITORIES).toList();
    }

    public RepositoryView repository(
            String tenantId, String userId, McpConnection connection,
            String owner, String name) {
        String safeOwner = identifier(owner, "owner");
        String safeName = identifier(name, "name");
        List<RepositoryView> candidates = new ArrayList<>();
        candidates.addAll(search(tenantId, userId, connection.id(),
                safeName + " in:name user:" + safeOwner, 20));
        if (candidates.stream().noneMatch(value -> exact(value, safeOwner, safeName))) {
            candidates.addAll(search(tenantId, userId, connection.id(),
                    safeName + " in:name org:" + safeOwner, 20));
        }
        return candidates.stream().filter(value -> exact(value, safeOwner, safeName))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                        "GitHub MCP repository was not found"));
    }

    public List<RepositoryView> searchRepositories(
            String tenantId,
            String userId,
            McpConnection connection,
            String query,
            int limit) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isEmpty() || normalized.length() > 256
                || normalized.contains("\r") || normalized.contains("\n")) {
            throw new IllegalArgumentException("GitHub repository query is invalid");
        }
        return search(
                tenantId, userId, connection.id(), normalized,
                Math.max(1, Math.min(limit, 20)));
    }

    private List<RepositoryView> search(
            String tenantId, String userId, String connectionId,
            String query, int perPage) {
        McpRemoteToolApplicationApi.ToolResult result;
        try {
            result = tools.callTool(new McpRemoteToolApplicationApi.CallCommand(
                    tenantId, userId, connectionId, "search_repositories",
                    Map.of("query", query, "page", 1, "perPage", perPage,
                            "minimal_output", true)));
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw failure(HttpStatus.BAD_GATEWAY,
                    "GITHUB_MCP_REPOSITORY_UPSTREAM_UNAVAILABLE",
                    "GitHub MCP repository service is unavailable");
        }
        if (result.error()) throw remoteFailure(result.text());
        try {
            JsonNode root = json(result.structuredContent(), result.text());
            JsonNode items = root.path("items");
            if (!items.isArray() || items.size() > MAX_REPOSITORIES) {
                throw new IllegalStateException("invalid items");
            }
            List<RepositoryView> values = new ArrayList<>();
            for (JsonNode item : items) values.add(repository(item));
            return values;
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw failure(HttpStatus.BAD_GATEWAY,
                    "GITHUB_MCP_REPOSITORY_RESPONSE_INVALID",
                    "GitHub MCP repository response is invalid");
        }
    }

    private static BusinessException remoteFailure(String remoteText) {
        String normalized = remoteText == null ? "" : remoteText
                .substring(0, Math.min(remoteText.length(), MAX_REMOTE_ERROR_CHARACTERS))
                .toLowerCase(Locale.ROOT);
        if (normalized.contains("rate limit") || normalized.contains("too many requests")
                || normalized.contains("status 429") || normalized.contains("http 429")) {
            return failure(HttpStatus.TOO_MANY_REQUESTS,
                    "GITHUB_MCP_REPOSITORY_RATE_LIMITED",
                    "GitHub MCP repository search is rate limited");
        }
        if (normalized.contains("bad credentials") || normalized.contains("unauthorized")
                || normalized.contains("status 401") || normalized.contains("http 401")) {
            return failure(HttpStatus.CONFLICT,
                    "GITHUB_MCP_REPOSITORY_REAUTH_REQUIRED",
                    "GitHub MCP authorization must be renewed");
        }
        if (normalized.contains("insufficient scope") || normalized.contains("missing scope")
                || normalized.contains("required scope") || normalized.contains("scope required")) {
            return failure(HttpStatus.FORBIDDEN,
                    "GITHUB_MCP_REPOSITORY_SCOPE_REQUIRED",
                    "GitHub MCP repository permission is required");
        }
        if (normalized.contains("forbidden") || normalized.contains("resource not accessible")
                || normalized.contains("status 403") || normalized.contains("http 403")) {
            return failure(HttpStatus.FORBIDDEN,
                    "GITHUB_MCP_REPOSITORY_ACCESS_DENIED",
                    "GitHub MCP repository access is denied");
        }
        if (normalized.contains("validation failed") || normalized.contains("invalid query")
                || normalized.contains("status 422") || normalized.contains("http 422")) {
            return failure(HttpStatus.BAD_GATEWAY,
                    "GITHUB_MCP_REPOSITORY_QUERY_REJECTED",
                    "GitHub MCP rejected the repository query");
        }
        return failure(HttpStatus.BAD_GATEWAY,
                "GITHUB_MCP_REPOSITORY_REMOTE_REJECTED",
                "GitHub MCP rejected the repository search");
    }

    private static String safeCode(RuntimeException error) {
        return error instanceof BusinessException business
                ? business.getCode() : "GITHUB_MCP_REPOSITORY_UPSTREAM_UNAVAILABLE";
    }

    private static RuntimeException preferredFailure(
            RuntimeException current, RuntimeException candidate) {
        if (current == null) return candidate;
        if (isQueryRejected(current) && !isQueryRejected(candidate)) return candidate;
        return current;
    }

    private static boolean isQueryRejected(RuntimeException error) {
        return error instanceof BusinessException business
                && "GITHUB_MCP_REPOSITORY_QUERY_REJECTED".equals(business.getCode());
    }

    private static BusinessException failure(HttpStatus status, String code, String message) {
        return new BusinessException(message, status, code);
    }

    private RepositoryView repository(JsonNode node) {
        String fullName = text(node, "full_name", 201);
        String[] parts = fullName.split("/", -1);
        if (parts.length != 2) {
            throw new IllegalStateException("GitHub MCP repository full_name is invalid");
        }
        String owner = identifier(parts[0], "owner");
        String name = identifier(parts[1], "name");
        String expectedHtml = "https://github.com/" + owner + "/" + name;
        if (!expectedHtml.equalsIgnoreCase(text(node, "html_url", 1000))) {
            throw new IllegalStateException("GitHub MCP repository URL is invalid");
        }
        String branch = branch(text(node, "default_branch", 255));
        return new RepositoryView(
                owner + "/" + name, owner, name, expectedHtml, expectedHtml + ".git", branch,
                node.path("private").asBoolean(false), node.path("archived").asBoolean(false));
    }

    private JsonNode json(Object structured, String text) {
        try {
            if (structured != null) return json.valueToTree(structured);
            String value = text == null ? "" : text.trim();
            if (value.isEmpty() || value.length() > 2_000_000) {
                throw new IllegalStateException("GitHub MCP returned no bounded JSON content");
            }
            return json.readTree(value);
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("GitHub MCP returned invalid JSON content");
        }
    }

    private static void merge(
            Map<String, RepositoryView> target, List<RepositoryView> values) {
        for (RepositoryView value : values) {
            target.putIfAbsent(value.providerRepositoryId().toLowerCase(Locale.ROOT), value);
            if (target.size() >= MAX_REPOSITORIES) return;
        }
    }

    private static boolean exact(RepositoryView value, String owner, String name) {
        return value.owner().equalsIgnoreCase(owner) && value.name().equalsIgnoreCase(name);
    }

    private static String identifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 100
                || !normalized.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalStateException("GitHub MCP returned invalid " + field);
        }
        return normalized;
    }

    private static String branch(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 255 || normalized.contains("..")
                || normalized.contains(" ") || normalized.contains("~")
                || normalized.contains("^") || normalized.contains(":")) {
            throw new IllegalStateException("GitHub MCP returned invalid default branch");
        }
        return normalized;
    }

    private static String text(JsonNode node, String field, int max) {
        JsonNode value = node == null ? null : node.get(field);
        String normalized = value == null || value.isNull() ? "" : value.asText().trim();
        if (normalized.isEmpty() || normalized.length() > max) {
            throw new IllegalStateException("GitHub MCP response missing " + field);
        }
        return normalized;
    }

    public record Account(String accountId, String login) {
    }
}
