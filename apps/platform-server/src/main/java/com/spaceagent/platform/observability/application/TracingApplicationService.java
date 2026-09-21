package com.spaceagent.platform.observability.application;

import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.observability.api.TracingApplicationApi;
import com.spaceagent.platform.observability.domain.TraceFilter;
import com.spaceagent.platform.observability.domain.TraceSpan;
import com.spaceagent.platform.observability.domain.TraceStats;
import com.spaceagent.platform.observability.domain.TraceStatus;
import com.spaceagent.platform.observability.domain.TraceSummary;
import com.spaceagent.platform.observability.domain.TracingQueryRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@Transactional(readOnly = true)
public class TracingApplicationService implements TracingApplicationApi {

    private final TracingQueryRepository repository;
    private final IdentityApplicationApi identity;
    private final TimeProvider time;

    public TracingApplicationService(
            TracingQueryRepository repository,
            IdentityApplicationApi identity,
            TimeProvider time) {
        this.repository = repository;
        this.identity = identity;
        this.time = time;
    }

    @Override
    public TraceListResponse list(TraceQuery query) {
        requireActiveMember(query.tenantId(), query.userId());
        int limit = query.limit() <= 0 ? 25 : query.limit();
        int offset = Math.max(0, query.offset());
        if (limit > 100) {
            throw badRequest("limit must not exceed 100");
        }
        var page = repository.search(filter(
                query.tenantId(), query.userId(), query.status(), query.agentId(),
                query.sessionId(), query.keyword(), query.minDurationMs(),
                query.maxDurationMs(), query.since(), query.until()), limit, offset);
        return new TraceListResponse(
                page.traces().stream().map(TracingApplicationService::view).toList(),
                page.total(), page.limit(), page.offset());
    }

    @Override
    public TraceDetailResponse get(TraceByIdQuery query) {
        requireActiveMember(query.tenantId(), query.userId());
        var detail = repository.find(query.tenantId(), query.userId(), query.traceId())
                .orElseThrow(() -> new BusinessException(
                        "Trace not found", HttpStatus.NOT_FOUND, "TRACE_NOT_FOUND"));
        return new TraceDetailResponse(
                view(detail.trace()), detail.spans().stream()
                        .map(TracingApplicationService::view).toList());
    }

    @Override
    public TraceStatsResponse stats(TraceStatsQuery query) {
        requireActiveMember(query.tenantId(), query.userId());
        String range = normalizeRange(query.range());
        Instant since = query.since() == null ? since(range) : query.since();
        TraceStats stats = repository.stats(filter(
                query.tenantId(), query.userId(), query.status(), query.agentId(),
                query.sessionId(), query.keyword(), query.minDurationMs(),
                query.maxDurationMs(), since, query.until()));
        return new TraceStatsResponse(
                stats.totalTraces(), stats.successTraces(), stats.errorTraces(),
                stats.abortedTraces(), stats.averageDurationMs(), stats.p50DurationMs(),
                stats.p95DurationMs(), stats.averageFirstTokenMs(), stats.totalTokens(),
                stats.totalCostUsd(), range);
    }

    private TraceFilter filter(
            String tenantId,
            String userId,
            String status,
            String agentId,
            String sessionId,
            String keyword,
            Long minDurationMs,
            Long maxDurationMs,
            Instant since,
            Instant until) {
        if (minDurationMs != null && minDurationMs < 0
                || maxDurationMs != null && maxDurationMs < 0
                || minDurationMs != null && maxDurationMs != null
                    && minDurationMs > maxDurationMs) {
            throw badRequest("Invalid trace duration range");
        }
        if (since != null && until != null && since.isAfter(until)) {
            throw badRequest("since must not be after until");
        }
        String normalizedKeyword = normalize(keyword, 200);
        return new TraceFilter(
                tenantId, userId, parseStatus(status), normalize(agentId, 80),
                normalize(sessionId, 80), normalizedKeyword,
                minDurationMs, maxDurationMs, since, until);
    }

    private void requireActiveMember(String tenantId, String userId) {
        identity.findTenantMembership(tenantId, userId)
                .filter(value -> value.status() == TenantMembershipStatus.ACTIVE)
                .orElseThrow(() -> new BusinessException(
                        "Active Organization membership is required",
                        HttpStatus.FORBIDDEN, "TRACING_MEMBERSHIP_REQUIRED"));
    }

    private Instant since(String range) {
        Instant now = time.now();
        return switch (range) {
            case "today" -> now.truncatedTo(ChronoUnit.DAYS);
            case "30d" -> now.minus(30, ChronoUnit.DAYS);
            case "all" -> Instant.EPOCH;
            default -> now.minus(7, ChronoUnit.DAYS);
        };
    }

    private static String normalizeRange(String value) {
        String range = value == null || value.isBlank() ? "7d" : value.trim().toLowerCase();
        if (!java.util.List.of("today", "7d", "30d", "all").contains(range)) {
            throw badRequest("Unsupported trace range: " + value);
        }
        return range;
    }

    private static TraceStatus parseStatus(String value) {
        try {
            return TraceStatus.fromWire(value);
        } catch (IllegalArgumentException error) {
            throw badRequest("Unsupported trace status: " + value);
        }
    }

    private static String normalize(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw badRequest("Trace filter exceeds maximum length");
        }
        return normalized;
    }

    private static TraceSummaryView view(TraceSummary trace) {
        return new TraceSummaryView(
                trace.id(), trace.organizationId(), trace.sessionId(), trace.sessionName(),
                trace.agentId(), trace.agentName(), trace.userId(), trace.rootSpanId(),
                trace.status().wireName(), trace.error(), trace.errorMessage(),
                trace.startTime(), trace.endTime(), trace.durationMs(), trace.firstTokenMs(),
                trace.llmMs(), trace.toolWallMs(), trace.toolDurationSumMs(), trace.spanCount(),
                trace.llmTurns(), trace.toolCalls(), trace.inputTokens(), trace.outputTokens(),
                trace.totalTokens(), trace.cacheCreateTokens(), trace.cacheReadTokens(),
                trace.costUsd(), trace.costEstimated(), trace.metadata(),
                trace.createdAt(), trace.updatedAt());
    }

    private static TraceSpanView view(TraceSpan span) {
        return new TraceSpanView(
                span.id(), span.traceId(), span.parentSpanId(), span.spanType().wireName(),
                span.name(), span.status().wireName(), span.error(), span.errorMessage(),
                span.startTime(), span.endTime(), span.durationMs(), span.model(),
                span.inputTokens(), span.outputTokens(), span.totalTokens(),
                span.cacheCreateTokens(), span.cacheReadTokens(), span.inputPreview(),
                span.outputPreview(), span.metadata(), span.createdAt(), span.updatedAt());
    }

    private static BusinessException badRequest(String message) {
        return new BusinessException(
                message, HttpStatus.BAD_REQUEST, "TRACE_QUERY_INVALID");
    }
}
