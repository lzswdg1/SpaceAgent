package com.spaceagent.platform.tooling.infrastructure.memory;

import com.spaceagent.platform.tooling.domain.*;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryMcpInvocationLedgerRepository
        implements McpInvocationLedgerRepository, McpCheckoutGrantRepository {
    private final Map<String, McpInvocationLedger> entries = new ConcurrentHashMap<>();
    private final Map<String, StoredGrant> grants = new ConcurrentHashMap<>();
    private final TimeProvider time;

    public InMemoryMcpInvocationLedgerRepository() {
        this(Instant::now);
    }

    public InMemoryMcpInvocationLedgerRepository(TimeProvider time) {
        this.time = time;
    }

    @Override
    public ClaimDecision claim(ClaimRequest request) {
        AtomicReference<ClaimDecision> answer = new AtomicReference<>();
        entries.compute(key(request), (key, current) -> {
            Instant now = time.now();
            if (current == null) {
                McpInvocationLedger claimed = new McpInvocationLedger(
                        request.id(), request.tenantId(), request.userId(), request.connectionId(),
                        request.operationKey(), request.idempotencyKeyHash(), request.toolName(),
                        request.argumentsJson(), request.inputHash(), McpInvocationStatus.RUNNING,
                        null, null, request.claimToken(), request.claimOwner(),
                        now.plusSeconds(request.leaseSeconds()), 1, now, now, null);
                answer.set(new ClaimDecision(McpInvocationClaimType.CLAIMED, claimed));
                return claimed;
            }
            if (!current.inputHash().equals(request.inputHash())) {
                answer.set(new ClaimDecision(McpInvocationClaimType.CONFLICT, current));
                return current;
            }
            if (terminal(current.status())) {
                answer.set(new ClaimDecision(McpInvocationClaimType.REPLAY, current));
                return current;
            }
            if (current.status() == McpInvocationStatus.UNKNOWN) {
                answer.set(new ClaimDecision(McpInvocationClaimType.UNKNOWN, current));
                return current;
            }
            if (now.isBefore(current.leaseUntil())) {
                answer.set(new ClaimDecision(McpInvocationClaimType.BUSY, current));
                return current;
            }
            McpInvocationLedger unknown = unknown(current, "MCP_INVOCATION_LEASE_EXPIRED", now);
            answer.set(new ClaimDecision(McpInvocationClaimType.UNKNOWN, unknown));
            return unknown;
        });
        return answer.get();
    }

    @Override
    public Transition complete(CompleteRequest request) {
        AtomicReference<Transition> answer = new AtomicReference<>();
        entries.replaceAll((key, current) -> {
            if (!current.id().equals(request.id())) return current;
            if (terminal(current.status())) {
                answer.set(new Transition(McpInvocationTransitionType.CURRENT_TERMINAL, current));
                return current;
            }
            if (current.status() == McpInvocationStatus.UNKNOWN) {
                answer.set(new Transition(McpInvocationTransitionType.CURRENT_UNKNOWN, current));
                return current;
            }
            Instant now = time.now();
            if (!now.isBefore(current.leaseUntil())) {
                McpInvocationLedger unknown = unknown(current, "MCP_INVOCATION_LATE_RESULT", now);
                answer.set(new Transition(McpInvocationTransitionType.CURRENT_UNKNOWN, unknown));
                return unknown;
            }
            if (!Objects.equals(request.claimToken(), current.claimToken())
                    || request.expectedRevision() != current.revision()) {
                answer.set(new Transition(McpInvocationTransitionType.CLAIM_LOST, current));
                return current;
            }
            McpInvocationLedger completed = new McpInvocationLedger(
                    current.id(), current.tenantId(), current.userId(), current.connectionId(),
                    current.operationKey(), current.idempotencyKeyHash(), current.toolName(),
                    current.argumentsJson(), current.inputHash(), request.status(), request.resultJson(),
                    request.errorCode(), current.claimToken(), current.claimOwner(), current.leaseUntil(),
                    current.revision() + 1, current.startedAt(), now, now);
            answer.set(new Transition(McpInvocationTransitionType.APPLIED, completed));
            return completed;
        });
        if (answer.get() == null) throw new IllegalStateException("MCP invocation not found");
        return answer.get();
    }

    @Override
    public Transition markUnknown(UnknownRequest request) {
        AtomicReference<Transition> answer = new AtomicReference<>();
        entries.replaceAll((key, current) -> {
            if (!current.id().equals(request.id())) return current;
            if (terminal(current.status())) {
                answer.set(new Transition(McpInvocationTransitionType.CURRENT_TERMINAL, current));
                return current;
            }
            if (current.status() == McpInvocationStatus.UNKNOWN) {
                answer.set(new Transition(McpInvocationTransitionType.CURRENT_UNKNOWN, current));
                return current;
            }
            if (!Objects.equals(request.claimToken(), current.claimToken())
                    || request.expectedRevision() != current.revision()) {
                answer.set(new Transition(McpInvocationTransitionType.CLAIM_LOST, current));
                return current;
            }
            McpInvocationLedger unknown = unknown(current, request.errorCode(), time.now());
            answer.set(new Transition(McpInvocationTransitionType.APPLIED, unknown));
            return unknown;
        });
        if (answer.get() == null) throw new IllegalStateException("MCP invocation not found");
        return answer.get();
    }

    @Override
    public McpInvocationTransitionType completeWithGrant(CompleteGrantRequest request) {
        AtomicReference<McpInvocationTransitionType> answer = new AtomicReference<>();
        entries.replaceAll((key, current) -> {
            if (!current.id().equals(request.invocationId())) return current;
            if (terminal(current.status())) {
                answer.set(McpInvocationTransitionType.CURRENT_TERMINAL);
                return current;
            }
            if (current.status() == McpInvocationStatus.UNKNOWN) {
                answer.set(McpInvocationTransitionType.CURRENT_UNKNOWN);
                return current;
            }
            Instant now = time.now();
            if (!now.isBefore(current.leaseUntil())) {
                answer.set(McpInvocationTransitionType.CURRENT_UNKNOWN);
                return unknown(current, "MCP_CHECKOUT_LATE_RESULT", now);
            }
            if (!Objects.equals(request.claimToken(), current.claimToken())
                    || request.expectedRevision() != current.revision()
                    || !request.tenantId().equals(current.tenantId())
                    || !request.userId().equals(current.userId())) {
                answer.set(McpInvocationTransitionType.CLAIM_LOST);
                return current;
            }
            McpInvocationLedger completed = new McpInvocationLedger(
                    current.id(), current.tenantId(), current.userId(), current.connectionId(),
                    current.operationKey(), current.idempotencyKeyHash(), current.toolName(),
                    current.argumentsJson(), current.inputHash(), McpInvocationStatus.SUCCEEDED,
                    request.encryptedGrantJson(), null, current.claimToken(), current.claimOwner(),
                    current.leaseUntil(), current.revision() + 1, current.startedAt(), now, now);
            grants.put(current.id(), new StoredGrant(
                    current.id(), request.encryptedGrantJson(), request.expiresAt()));
            answer.set(McpInvocationTransitionType.APPLIED);
            return completed;
        });
        if (answer.get() == null) throw new IllegalStateException("MCP invocation not found");
        return answer.get();
    }

    @Override
    public java.util.Optional<StoredGrant> findAvailable(GrantQuery query) {
        McpInvocationLedger invocation = entries.values().stream()
                .filter(value -> value.id().equals(query.invocationId())
                        && value.tenantId().equals(query.tenantId())
                        && value.userId().equals(query.userId())
                        && value.status() == McpInvocationStatus.SUCCEEDED)
                .findFirst().orElse(null);
        if (invocation == null) return java.util.Optional.empty();
        StoredGrant grant = grants.get(query.invocationId());
        if (grant == null) return java.util.Optional.empty();
        if (!time.now().isBefore(grant.expiresAt())) {
            consume(query);
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(grant);
    }

    @Override
    public void consume(GrantQuery query) {
        grants.remove(query.invocationId());
        entries.replaceAll((key, current) -> {
            if (!current.id().equals(query.invocationId())
                    || !current.tenantId().equals(query.tenantId())
                    || !current.userId().equals(query.userId())
                    || current.status() != McpInvocationStatus.SUCCEEDED
                    || current.resultJson() == null) {
                return current;
            }
            Instant now = time.now();
            return new McpInvocationLedger(
                    current.id(), current.tenantId(), current.userId(), current.connectionId(),
                    current.operationKey(), current.idempotencyKeyHash(), current.toolName(),
                    current.argumentsJson(), current.inputHash(), current.status(), null,
                    current.errorCode(), current.claimToken(), current.claimOwner(),
                    current.leaseUntil(), current.revision() + 1, current.startedAt(), now,
                    current.completedAt());
        });
    }

    @Override
    public int redactExpired() {
        var expired = grants.values().stream()
                .filter(value -> !time.now().isBefore(value.expiresAt()))
                .map(value -> value.invocationId())
                .toList();
        for (String id : expired) {
            entries.values().stream().filter(value -> value.id().equals(id)).findFirst()
                    .ifPresent(value -> consume(new GrantQuery(
                            id, value.tenantId(), value.userId())));
        }
        return expired.size();
    }

    private static String key(ClaimRequest request) {
        return request.tenantId() + '\0' + request.userId() + '\0'
                + request.operationKey() + '\0' + request.idempotencyKeyHash();
    }

    private static boolean terminal(McpInvocationStatus status) {
        return status == McpInvocationStatus.SUCCEEDED || status == McpInvocationStatus.FAILED;
    }

    private static McpInvocationLedger unknown(
            McpInvocationLedger current, String code, Instant now) {
        return new McpInvocationLedger(
                current.id(), current.tenantId(), current.userId(), current.connectionId(),
                current.operationKey(), current.idempotencyKeyHash(), current.toolName(),
                current.argumentsJson(), current.inputHash(), McpInvocationStatus.UNKNOWN,
                current.resultJson(), code, null, current.claimOwner(), current.leaseUntil(),
                current.revision() + 1, current.startedAt(), now, null);
    }
}
