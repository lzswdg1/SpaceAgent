package com.spaceagent.platform.tooling.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.domain.McpCapabilitySnapshot;
import com.spaceagent.platform.tooling.domain.McpConnection;
import com.spaceagent.platform.tooling.domain.McpConnectionQualificationRepository;
import com.spaceagent.platform.tooling.domain.McpConnectionState;
import com.spaceagent.platform.tooling.domain.McpInstallationScope;
import com.spaceagent.platform.tooling.domain.McpInstallationState;
import com.spaceagent.platform.tooling.domain.McpMarketplaceRepository;
import com.spaceagent.platform.tooling.domain.McpRemoteToolGateway;
import com.spaceagent.platform.tooling.domain.ToolEffectVerifier;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.time.TimeProvider;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class GithubIssueUpdateMcpEffectVerifier implements ToolEffectVerifier {
    private static final int MAX_RESPONSE_BYTES = 32_000;
    private static final Set<String> OUTER_KEYS = Set.of(
            "connectionId", "remoteTool", "arguments");
    private static final Set<String> UPDATE_KEYS = Set.of(
            "method", "owner", "repo", "issue_number", "title", "body", "state");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(api[_-]?key|authorization|bearer|password|private[ _-]?key)\\s*[:=]");
    private static final Definition DEFINITION = new Definition(
            "github.issue_write.update.postcondition", 1, EffectKind.MCP_MUTATION,
            "mcp_call", "issue_write", Trust.LOCAL_REVIEWED,
            "github-issue-update/v1", 100_000, MAX_RESPONSE_BYTES, 10_000, true);

    private final McpMarketplaceRepository marketplace;
    private final McpConnectionQualificationRepository qualifications;
    private final McpConnectionAuthorizationService authorization;
    private final McpRemoteToolGateway gateway;
    private final ObjectMapper json;
    private final TimeProvider time;

    public GithubIssueUpdateMcpEffectVerifier(
            McpMarketplaceRepository marketplace,
            McpConnectionQualificationRepository qualifications,
            McpConnectionAuthorizationService authorization,
            McpRemoteToolGateway gateway,
            ObjectMapper json,
            TimeProvider time) {
        this.marketplace = marketplace;
        this.qualifications = qualifications;
        this.authorization = authorization;
        this.gateway = gateway;
        this.json = json;
        this.time = time;
    }

    @Override
    public Definition definition() {
        return DEFINITION;
    }

    @Override
    public Evidence verify(Request request) {
        request.validateFor(DEFINITION);
        McpScope scope = (McpScope) request.scope();
        ExpectedIssue expected = expected(request.canonicalInput(), scope);
        McpConnection connection = exactConnection(request, scope);
        McpCapabilitySnapshot snapshot = exactSnapshot(scope);
        requireTools(snapshot);
        McpConnectionAuthorizationService.AuthorizedConnection authorized =
                authorization.authorize(connection);
        if (authorized.connection().revision() != scope.connectionRevision()) {
            throw scopeMismatch();
        }

        Instant started = time.now();
        McpRemoteToolGateway.RemoteResult result;
        try {
            result = gateway.callTool(
                    authorized.connection(), authorized.authorization(), "issue_read",
                    Map.of("method", "get", "owner", expected.owner(),
                            "repo", expected.repo(), "issue_number", expected.number()));
        } catch (RuntimeException error) {
            return inconclusive(request, started, "MCP_POSTCONDITION_UNAVAILABLE");
        }
        Instant completed = time.now();
        if (result.error()) {
            return inconclusive(request, started, "MCP_POSTCONDITION_READ_FAILED");
        }
        Observation observation = observation(result);
        boolean matches = expected.matches(observation);
        return new Evidence(
                DEFINITION.verifierId(), DEFINITION.verifierVersion(),
                DEFINITION.evidenceSchemaVersion(),
                matches ? Verdict.PROVEN_APPLIED : Verdict.PROVEN_NOT_APPLIED,
                request.subjectSha256(), ToolEffectVerifier.sha256(observation.canonical()),
                observation.bytes(),
                matches ? "POSTCONDITION_MATCHED" : "POSTCONDITION_MISMATCH",
                started, completed);
    }

    private ExpectedIssue expected(String canonicalInput, McpScope scope) {
        try {
            Map<String, Object> outer = json.readValue(
                    canonicalInput, new TypeReference<Map<String, Object>>() { });
            if (!outer.keySet().equals(OUTER_KEYS)
                    || !scope.connectionId().equals(string(outer, "connectionId", 160))
                    || !scope.remoteToolName().equals(string(outer, "remoteTool", 160))) {
                throw unsupported();
            }
            Map<String, Object> arguments = json.convertValue(
                    outer.get("arguments"), new TypeReference<Map<String, Object>>() { });
            if (!UPDATE_KEYS.containsAll(arguments.keySet())
                    || !"update".equals(string(arguments, "method", 20))) {
                throw unsupported();
            }
            String owner = identifier(arguments, "owner");
            String repo = identifier(arguments, "repo");
            int number = positiveInteger(arguments.get("issue_number"));
            String title = optional(arguments, "title", 500);
            String body = optional(arguments, "body", 50_000);
            String state = optional(arguments, "state", 20);
            if (title == null && body == null && state == null) throw unsupported();
            if (state != null && !Set.of("open", "closed").contains(state)) {
                throw unsupported();
            }
            return new ExpectedIssue(owner, repo, number, title, body, state);
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw unsupported();
        }
    }

    private McpConnection exactConnection(Request request, McpScope scope) {
        McpConnection connection = marketplace.findConnection(scope.connectionId())
                .filter(value -> value.tenantId().equals(request.tenantId()))
                .filter(value -> value.state() == McpConnectionState.ACTIVE)
                .filter(value -> value.revision() == scope.connectionRevision())
                .orElseThrow(GithubIssueUpdateMcpEffectVerifier::scopeMismatch);
        var installation = marketplace.findInstallation(connection.installationId())
                .filter(value -> value.tenantId().equals(request.tenantId()))
                .filter(value -> value.state() == McpInstallationState.INSTALLED)
                .orElseThrow(GithubIssueUpdateMcpEffectVerifier::scopeMismatch);
        if (installation.scope() == McpInstallationScope.USER
                && !installation.subjectId().equals(request.ownerUserId())) {
            throw scopeMismatch();
        }
        return connection;
    }

    private McpCapabilitySnapshot exactSnapshot(McpScope scope) {
        return qualifications.findCurrentSnapshot(scope.connectionId())
                .filter(value -> value.id().equals(scope.capabilitySnapshotId()))
                .filter(value -> value.connectionRevision() == scope.connectionRevision())
                .filter(value -> ("sha256:" + value.snapshotSha256())
                        .equals(scope.snapshotSha256()))
                .orElseThrow(GithubIssueUpdateMcpEffectVerifier::scopeMismatch);
    }

    private void requireTools(McpCapabilitySnapshot snapshot) {
        try {
            JsonNode tools = json.readTree(snapshot.toolsJson());
            JsonNode write = find(tools, "issue_write");
            JsonNode read = find(tools, "issue_read");
            if (write == null || write.path("readOnly").asBoolean(true)
                    || read == null || !read.path("readOnly").asBoolean(false)
                    || read.path("destructive").asBoolean(false)) {
                throw scopeMismatch();
            }
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw scopeMismatch();
        }
    }

    private Observation observation(McpRemoteToolGateway.RemoteResult result) {
        try {
            JsonNode node;
            int bytes;
            if (result.structuredContent() != null) {
                byte[] encoded = json.writeValueAsBytes(result.structuredContent());
                bytes = encoded.length;
                node = json.readTree(encoded);
            } else {
                String text = result.text() == null ? "" : result.text();
                bytes = text.getBytes(StandardCharsets.UTF_8).length;
                if (SECRET.matcher(text).find()) throw new IllegalArgumentException();
                node = json.readTree(text);
            }
            if (bytes <= 0 || bytes > MAX_RESPONSE_BYTES) throw new IllegalArgumentException();
            int number = positiveInteger(node.path("number").numberValue());
            String title = node.path("title").asText(null);
            String body = node.path("body").isNull() ? null : node.path("body").asText(null);
            String state = node.path("state").asText(null);
            String url = node.path("html_url").asText(null);
            if (title == null || title.length() > 500 || body != null && body.length() > 50_000
                    || !Set.of("open", "closed").contains(state)
                    || url == null || url.length() > 1_000) {
                throw new IllegalArgumentException();
            }
            return new Observation(number, title, body, state, url, bytes);
        } catch (Exception error) {
            throw new BusinessException(
                    "MCP verifier returned unsafe evidence", HttpStatus.CONFLICT,
                    "TOOL_EFFECT_VERIFIER_EVIDENCE_INVALID");
        }
    }

    private Evidence inconclusive(Request request, Instant started, String code) {
        return new Evidence(
                DEFINITION.verifierId(), DEFINITION.verifierVersion(),
                DEFINITION.evidenceSchemaVersion(), Verdict.INCONCLUSIVE,
                request.subjectSha256(), null, 0, code, started, time.now());
    }

    private static JsonNode find(JsonNode tools, String name) {
        if (!tools.isArray()) return null;
        for (JsonNode tool : tools) {
            if (name.equals(tool.path("name").asText())) return tool;
        }
        return null;
    }

    private static String identifier(Map<String, Object> values, String key) {
        String value = string(values, key, 100);
        if (!IDENTIFIER.matcher(value).matches()) throw unsupported();
        return value;
    }

    private static String string(Map<String, Object> values, String key, int max) {
        Object raw = values.get(key);
        if (!(raw instanceof String value) || value.isBlank() || value.length() > max) {
            throw unsupported();
        }
        return value;
    }

    private static String optional(Map<String, Object> values, String key, int max) {
        if (!values.containsKey(key)) return null;
        Object raw = values.get(key);
        if (!(raw instanceof String value) || value.length() > max) throw unsupported();
        return value;
    }

    private static int positiveInteger(Object value) {
        if (!(value instanceof Number number)) throw unsupported();
        double decimal = number.doubleValue();
        int integer = number.intValue();
        if (integer <= 0 || decimal != integer) throw unsupported();
        return integer;
    }

    private static BusinessException unsupported() {
        return new BusinessException(
                "MCP mutation has no supported exact postcondition", HttpStatus.CONFLICT,
                "TOOL_EFFECT_VERIFIER_UNSUPPORTED");
    }

    private static BusinessException scopeMismatch() {
        return new BusinessException(
                "MCP verifier scope is stale or mismatched", HttpStatus.CONFLICT,
                "TOOL_EFFECT_VERIFIER_SCOPE_MISMATCH");
    }

    private record ExpectedIssue(
            String owner, String repo, int number, String title, String body, String state) {
        private boolean matches(Observation actual) {
            String expectedUrl = "https://github.com/" + owner + "/" + repo
                    + "/issues/" + number;
            return number == actual.number() && expectedUrl.equals(actual.url())
                    && (title == null || title.equals(actual.title()))
                    && (body == null || body.equals(actual.body()))
                    && (state == null || state.equals(actual.state()));
        }
    }

    private record Observation(
            int number, String title, String body, String state, String url, int bytes) {
        private String canonical() {
            return String.join("\n", Integer.toString(number), title,
                    body == null ? "" : body, state, url);
        }
    }

}
