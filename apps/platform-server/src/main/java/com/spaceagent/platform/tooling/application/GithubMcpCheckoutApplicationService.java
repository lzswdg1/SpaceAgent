package com.spaceagent.platform.tooling.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.tooling.api.GithubMcpCheckoutApplicationApi;
import com.spaceagent.platform.tooling.api.McpRemoteToolApplicationApi;
import com.spaceagent.platform.tooling.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
public class GithubMcpCheckoutApplicationService implements GithubMcpCheckoutApplicationApi {
    private static final long CLAIM_LEASE_SECONDS = 90;
    private static final long MIN_GRANT_SECONDS = 180;
    private static final long MAX_GRANT_SECONDS = 600;
    private static final String TOOL_NAME = "github_prepare_checkout";

    private final McpMarketplaceRepository marketplace;
    private final McpInvocationLedgerRepository invocations;
    private final McpCheckoutGrantRepository grants;
    private final McpRemoteToolApplicationApi tools;
    private final McpConnectionSecretCipher cipher;
    private final McpConnectionAuthorizationService authorization;
    private final ObjectMapper json;
    private final IdGenerator ids;
    private final TimeProvider time;

    public GithubMcpCheckoutApplicationService(
            McpMarketplaceRepository marketplace,
            McpInvocationLedgerRepository invocations,
            McpCheckoutGrantRepository grants,
            McpRemoteToolApplicationApi tools,
            McpConnectionSecretCipher cipher,
            McpConnectionAuthorizationService authorization,
            ObjectMapper json,
            IdGenerator ids,
            TimeProvider time) {
        this.marketplace = marketplace;
        this.invocations = invocations;
        this.grants = grants;
        this.tools = tools;
        this.cipher = cipher;
        this.authorization = authorization;
        this.json = json;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public CheckoutGrantView prepare(PrepareCommand command) {
        String workspaceId = uuid(command.workspaceId(), "workspaceId");
        String sourceId = uuid(command.sourceRepositoryId(), "sourceRepositoryId");
        String repositoryId = repositoryId(command.providerRepositoryId());
        String cloneUrl = cloneUrl(command.cloneUrl());
        McpConnection connection = requireGithubConnection(
                command.tenantId(), command.userId(), command.connectionId());
        Map<String, String> officialAuthorization = null;
        if (GithubMcpProfiles.isOfficialRemote(connection)) {
            McpConnectionAuthorizationService.AuthorizedConnection resolved =
                    authorization.authorize(connection);
            connection = resolved.connection();
            officialAuthorization = resolved.authorization();
        }
        String operationName = GithubMcpProfiles.isOfficialRemote(connection)
                ? "github_official_checkout_grant" : TOOL_NAME;
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("workspaceId", workspaceId);
        arguments.put("repositoryId", repositoryId);
        arguments.put("cloneUrl", cloneUrl);
        String argumentsJson = write(arguments);
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("tenantId", command.tenantId());
        input.put("userId", command.userId());
        input.put("workspaceId", workspaceId);
        input.put("sourceRepositoryId", sourceId);
        input.put("connectionId", connection.id());
        input.put("arguments", arguments);
        String claimToken = UUID.randomUUID().toString();
        McpInvocationLedgerRepository.ClaimDecision claim = invocations.claim(
                new McpInvocationLedgerRepository.ClaimRequest(
                        ids.nextId(), command.tenantId(), command.userId(), connection.id(),
                        "github-workspace-checkout:" + workspaceId, hash(workspaceId),
                        operationName, argumentsJson, hash(write(input)), claimToken,
                        "platform:" + UUID.randomUUID(), CLAIM_LEASE_SECONDS));
        switch (claim.type()) {
            case REPLAY -> {
                return available(command.tenantId(), command.userId(), claim.ledger());
            }
            case BUSY -> throw conflict(
                    "GitHub MCP checkout is already running", "MCP_CHECKOUT_IN_PROGRESS");
            case UNKNOWN -> throw conflict(
                    "GitHub MCP checkout outcome is unknown", "MCP_CHECKOUT_OUTCOME_UNKNOWN");
            case CONFLICT -> throw conflict(
                    "Workspace checkout input conflicts with its durable claim",
                    "MCP_CHECKOUT_IDEMPOTENCY_CONFLICT");
            case CLAIMED -> {
                // The short PostgreSQL claim transaction committed before the MCP call.
            }
        }

        ParsedGrant parsed;
        if (GithubMcpProfiles.isOfficialRemote(connection)) {
            try {
                parsed = officialGrant(connection, officialAuthorization, cloneUrl);
            } catch (RuntimeException error) {
                completeFailure(claim.ledger(), claimToken, "MCP_CHECKOUT_AUTH_UNAVAILABLE");
                throw new BusinessException(
                        "GitHub MCP authorization must be renewed", HttpStatus.CONFLICT,
                        "MCP_CHECKOUT_REAUTH_REQUIRED");
            }
        } else {
            McpRemoteToolApplicationApi.ToolResult remote;
            try {
                remote = tools.callTool(new McpRemoteToolApplicationApi.CallCommand(
                        command.tenantId(), command.userId(), connection.id(),
                        TOOL_NAME, arguments));
            } catch (RuntimeException error) {
                invocations.markUnknown(new McpInvocationLedgerRepository.UnknownRequest(
                        claim.ledger().id(), claimToken, claim.ledger().revision(),
                        "MCP_CHECKOUT_REMOTE_OUTCOME_UNKNOWN"));
                throw conflict(
                        "GitHub MCP checkout outcome is unknown", "MCP_CHECKOUT_OUTCOME_UNKNOWN");
            }
            if (remote.error()) {
                completeFailure(claim.ledger(), claimToken, "MCP_CHECKOUT_REMOTE_REJECTED");
                throw new BusinessException(
                        "GitHub MCP rejected private checkout", HttpStatus.BAD_GATEWAY,
                        "MCP_CHECKOUT_REMOTE_FAILED");
            }
            try {
                parsed = parse(remote.structuredContent(), cloneUrl);
            } catch (RuntimeException error) {
                completeFailure(claim.ledger(), claimToken, "MCP_CHECKOUT_RESPONSE_INVALID");
                throw new BusinessException(
                        "GitHub MCP returned an invalid checkout grant", HttpStatus.BAD_GATEWAY,
                        "MCP_CHECKOUT_RESPONSE_INVALID");
            }
        }
        McpInvocationTransitionType completion;
        try {
            Map<String, Object> encrypted = new LinkedHashMap<>();
            encrypted.put("encryptedAuthorizationHeader",
                    cipher.encrypt(parsed.authorizationHeader()));
            String evidenceHash = hash(connection.id() + "\n" + workspaceId + "\n" + sourceId
                    + "\n" + repositoryId + "\n" + cloneUrl + "\n" + parsed.expiresAt());
            completion = grants.completeWithGrant(
                    new McpCheckoutGrantRepository.CompleteGrantRequest(
                            claim.ledger().id(), command.tenantId(), command.userId(), claimToken,
                            claim.ledger().revision(), write(encrypted), evidenceHash,
                            parsed.expiresAt()));
        } catch (RuntimeException error) {
            try {
                invocations.markUnknown(new McpInvocationLedgerRepository.UnknownRequest(
                        claim.ledger().id(), claimToken, claim.ledger().revision(),
                        "MCP_CHECKOUT_PERSISTENCE_OUTCOME_UNKNOWN"));
            } catch (RuntimeException ignored) {
                // A concurrent terminal commit will be observed through replay.
            }
            throw conflict(
                    "GitHub MCP checkout outcome is unknown", "MCP_CHECKOUT_OUTCOME_UNKNOWN");
        }
        if (completion == McpInvocationTransitionType.CURRENT_UNKNOWN) {
            throw conflict("GitHub MCP checkout outcome is unknown", "MCP_CHECKOUT_OUTCOME_UNKNOWN");
        }
        if (completion == McpInvocationTransitionType.CLAIM_LOST) {
            throw conflict("GitHub MCP checkout claim was lost", "MCP_CHECKOUT_CLAIM_LOST");
        }
        return available(command.tenantId(), command.userId(), claim.ledger());
    }

    @Override
    public void consume(ConsumeCommand command) {
        grants.consume(new McpCheckoutGrantRepository.GrantQuery(
                uuid(command.grantId(), "grantId"), command.tenantId(), command.userId()));
    }

    private CheckoutGrantView available(
            String tenantId, String userId, McpInvocationLedger invocation) {
        if (invocation.status() == McpInvocationStatus.FAILED) {
            throw new BusinessException(
                    "GitHub MCP rejected private checkout", HttpStatus.BAD_GATEWAY,
                    "MCP_CHECKOUT_REMOTE_FAILED");
        }
        McpCheckoutGrantRepository.StoredGrant grant = grants.findAvailable(
                        new McpCheckoutGrantRepository.GrantQuery(
                                invocation.id(), tenantId, userId))
                .orElseThrow(() -> conflict(
                        "GitHub MCP checkout grant is expired or consumed",
                        "MCP_CHECKOUT_GRANT_UNAVAILABLE"));
        try {
            Map<String, String> value = json.readValue(
                    grant.encryptedGrantJson(), new TypeReference<Map<String, String>>() {});
            String header = authorizationHeader(cipher.decrypt(
                    required(value.get("encryptedAuthorizationHeader"))));
            return new CheckoutGrantView(grant.invocationId(), header, grant.expiresAt());
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IllegalStateException("Unable to read encrypted MCP checkout grant");
        } catch (Exception error) {
            throw new IllegalStateException("Unable to read encrypted MCP checkout grant");
        }
    }

    private void completeFailure(
            McpInvocationLedger invocation, String claimToken, String errorCode) {
        McpInvocationLedgerRepository.Transition result = invocations.complete(
                new McpInvocationLedgerRepository.CompleteRequest(
                        invocation.id(), claimToken, invocation.revision(),
                        McpInvocationStatus.FAILED, null, errorCode));
        if (result.type() == McpInvocationTransitionType.CURRENT_UNKNOWN) {
            throw conflict("GitHub MCP checkout outcome is unknown", "MCP_CHECKOUT_OUTCOME_UNKNOWN");
        }
        if (result.type() == McpInvocationTransitionType.CLAIM_LOST) {
            throw conflict("GitHub MCP checkout claim was lost", "MCP_CHECKOUT_CLAIM_LOST");
        }
    }

    private ParsedGrant parse(Object value, String expectedCloneUrl) {
        if (value == null) throw new IllegalStateException("Missing checkout grant");
        Map<String, Object> data = json.convertValue(
                value, new TypeReference<Map<String, Object>>() {});
        String returnedCloneUrl = cloneUrl(text(data, "cloneUrl"));
        if (!expectedCloneUrl.equalsIgnoreCase(returnedCloneUrl)) {
            throw new IllegalStateException("Checkout repository mismatch");
        }
        String header = authorizationHeader(text(data, "authorizationHeader"));
        Instant expiresAt = Instant.parse(text(data, "expiresAt"));
        Instant now = time.now();
        if (expiresAt.isBefore(now.plusSeconds(MIN_GRANT_SECONDS))
                || expiresAt.isAfter(now.plusSeconds(MAX_GRANT_SECONDS))) {
            throw new IllegalStateException("Checkout grant expiry is invalid");
        }
        return new ParsedGrant(header, expiresAt);
    }

    private ParsedGrant officialGrant(
            McpConnection connection,
            Map<String, String> authorization,
            String expectedCloneUrl) {
        try {
            if (!GithubMcpProfiles.OFFICIAL_PROFILE.equals(authorization.get("oauth_profile"))) {
                throw new IllegalStateException("Official GitHub MCP OAuth profile is missing");
            }
            String tokenType = authorization.getOrDefault("token_type", "Bearer");
            if (!"Bearer".equalsIgnoreCase(tokenType)) {
                throw new IllegalStateException("Official GitHub MCP token type is invalid");
            }
            String accessToken = required(authorization.get("access_token"));
            if (!accessToken.matches("[\\x21-\\x7E]{1,4096}")) {
                throw new IllegalStateException("Official GitHub MCP token is invalid");
            }
            Instant now = time.now();
            Instant grantExpiry = now.plusSeconds(300);
            String tokenExpiry = authorization.get("expires_at");
            if (tokenExpiry != null && !tokenExpiry.isBlank()) {
                Instant safeTokenExpiry = Instant.parse(tokenExpiry).minusSeconds(30);
                if (safeTokenExpiry.isBefore(grantExpiry)) grantExpiry = safeTokenExpiry;
            }
            if (grantExpiry.isBefore(now.plusSeconds(MIN_GRANT_SECONDS))) {
                throw new IllegalStateException("Official GitHub MCP token expires too soon");
            }
            String login = required(connection.externalAccountName());
            if (!login.matches("[A-Za-z0-9_.-]{1,100}")) {
                throw new IllegalStateException("Official GitHub account login is invalid");
            }
            String basic = Base64.getEncoder().encodeToString(
                    (login + ":" + accessToken).getBytes(StandardCharsets.UTF_8));
            cloneUrl(expectedCloneUrl);
            return new ParsedGrant("Basic " + basic, grantExpiry);
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Unable to read official GitHub MCP authorization");
        }
    }

    private McpConnection requireGithubConnection(String tenantId, String userId, String id) {
        McpConnection connection = marketplace.findConnections(tenantId, userId).stream()
                .filter(value -> value.id().equals(id)
                        && value.state() == McpConnectionState.ACTIVE)
                .findFirst().orElseThrow(GithubMcpCheckoutApplicationService::notFound);
        McpInstallation installation = marketplace.findInstallation(connection.installationId())
                .orElseThrow(GithubMcpCheckoutApplicationService::notFound);
        marketplace.findEntry(installation.entryId())
                .filter(value -> value.slug().equals("github"))
                .orElseThrow(GithubMcpCheckoutApplicationService::notFound);
        return connection;
    }

    private static String authorizationHeader(String value) {
        String header = required(value).trim();
        if (header.length() > 4103 || header.contains("\r") || header.contains("\n")
                || !(header.startsWith("Bearer ") || header.startsWith("Basic "))
                || !header.substring(header.indexOf(' ') + 1).matches("[\\x21-\\x7E]{1,4096}")) {
            throw new IllegalStateException("Invalid checkout authorization header");
        }
        return header;
    }

    private static String cloneUrl(String value) {
        try {
            URI uri = URI.create(required(value).trim());
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !"github.com".equalsIgnoreCase(uri.getHost())
                    || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !uri.getPath().endsWith(".git")) {
                throw new IllegalArgumentException();
            }
            String[] parts = uri.getPath().replaceAll("^/+|/+$", "").split("/");
            if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9_.-]{1,100}")
                    || !parts[1].matches("[A-Za-z0-9_.-]{1,104}\\.git")) {
                throw new IllegalArgumentException();
            }
            return "https://github.com/" + parts[0] + "/" + parts[1];
        } catch (RuntimeException error) {
            throw invalid("cloneUrl is invalid");
        }
    }

    private static String repositoryId(String value) {
        String id = required(value).trim();
        if (id.length() > 180 || !id.matches("[A-Za-z0-9_.:/-]+")) {
            throw invalid("providerRepositoryId is invalid");
        }
        return id;
    }

    private static String uuid(String value, String field) {
        try {
            return UUID.fromString(value).toString();
        } catch (RuntimeException error) {
            throw invalid(field + " is invalid");
        }
    }

    private static String text(Map<String, Object> data, String field) {
        Object raw = data.get(field);
        String value = raw == null ? "" : String.valueOf(raw).trim();
        if (value.isEmpty() || value.length() > 5000) {
            throw new IllegalStateException("Checkout response is missing " + field);
        }
        return value;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize MCP checkout evidence");
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static String required(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("value required");
        return value;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(
                message, HttpStatus.BAD_REQUEST, "MCP_CHECKOUT_INPUT_INVALID");
    }

    private static BusinessException notFound() {
        return new BusinessException(
                "GitHub MCP connection not found", HttpStatus.NOT_FOUND,
                "MCP_CHECKOUT_NOT_FOUND");
    }

    private static BusinessException conflict(String message, String code) {
        return new BusinessException(message, HttpStatus.CONFLICT, code);
    }

    private record ParsedGrant(String authorizationHeader, Instant expiresAt) {
    }
}
