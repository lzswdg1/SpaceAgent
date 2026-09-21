package com.spaceagent.platform.observability.application;

import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.observability.api.ObservabilityApplicationApi;
import com.spaceagent.platform.observability.domain.MonitoringRange;
import com.spaceagent.platform.observability.domain.ObservabilityOverview;
import com.spaceagent.platform.observability.domain.ObservabilityQueryRepository;
import com.spaceagent.platform.observability.domain.ObservabilitySession;
import com.spaceagent.platform.observability.domain.ObservabilityTimeseriesPoint;
import com.spaceagent.platform.observability.domain.ObservabilityUsageRow;
import com.spaceagent.platform.observability.domain.UsageDimension;
import com.spaceagent.platform.runtime.api.CancelAgentRunCommand;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ObservabilityApplicationService implements ObservabilityApplicationApi {

    private final ObservabilityQueryRepository repository;
    private final IdentityApplicationApi identity;
    private final RuntimeApplicationApi runtime;
    private final TimeProvider time;

    public ObservabilityApplicationService(
            ObservabilityQueryRepository repository,
            IdentityApplicationApi identity,
            RuntimeApplicationApi runtime,
            TimeProvider time) {
        this.repository = repository;
        this.identity = identity;
        this.runtime = runtime;
        this.time = time;
    }

    @Override
    public OverviewResponse overview(MonitoringQuery query) {
        requireOrganizationAdmin(query.tenantId(), query.userId());
        MonitoringRange range = monitoringRange(query.range());
        ObservabilityOverview overview = repository.overview(
                query.tenantId(), range.since(time.now()), range.bucketSize());
        return new OverviewResponse(
                new MonitoringOverview(
                        overview.totalOrganizations(), overview.totalAgents(),
                        overview.totalSessions(), overview.activeSessions(),
                        overview.idleSessions(), overview.closedSessions(),
                        overview.runtimeActiveSessions(), overview.totalInputTokens(),
                        overview.totalOutputTokens(), overview.totalCacheReadTokens(),
                        overview.totalCacheCreateTokens(), dollars(overview.totalCostMicros()),
                        overview.peakConcurrent(), overview.incompleteCost(),
                        range.wireName(), range.bucketSize()),
                repository.timeseries(
                                query.tenantId(), range.since(time.now()), range.bucketSize())
                        .stream().map(ObservabilityApplicationService::view).toList());
    }

    @Override
    public UsageResponse usage(UsageQuery query) {
        requireOrganizationAdmin(query.tenantId(), query.userId());
        MonitoringRange range = monitoringRange(query.range());
        UsageDimension dimension = UsageDimension.fromWire(query.groupBy());
        List<ObservabilityUsageRow> values = repository.usage(
                query.tenantId(), range.since(time.now()), dimension, 201);
        boolean truncated = values.size() > 200;
        List<UsageRow> rows = values.stream().limit(200)
                .map(ObservabilityApplicationService::view)
                .toList();
        Long totalMicros = rows.stream()
                .map(UsageRow::costUsd)
                .filter(java.util.Objects::nonNull)
                .mapToLong(value -> Math.round(value * 1_000_000D))
                .sum();
        boolean anyCost = rows.stream().anyMatch(row -> row.costUsd() != null);
        return new UsageResponse(
                rows,
                anyCost ? dollars(totalMicros) : null,
                truncated,
                dimension.wireName(),
                range.wireName());
    }

    @Override
    public RealtimeStatus realtime(ActorQuery query) {
        requireOrganizationAdmin(query.tenantId(), query.userId());
        var realtime = repository.realtime(query.tenantId());
        List<PerOrganizationActive> active = realtime.runtimeActive() == 0
                ? List.of()
                : List.of(new PerOrganizationActive(
                        realtime.organizationId(), realtime.organizationName(),
                        realtime.runtimeActive()));
        return new RealtimeStatus(
                realtime.runtimeActive(), realtime.processing(), active, time.now());
    }

    @Override
    public SessionsResponse sessions(SessionsQuery query) {
        requireOrganizationAdmin(query.tenantId(), query.userId());
        MonitoringRange range = monitoringRange(query.range());
        String status = normalizeStatus(query.status());
        int page = query.page() < 1 ? 1 : query.page();
        int pageSize = query.pageSize() < 1 ? 25 : query.pageSize();
        if (pageSize > 100) {
            throw new IllegalArgumentException("pageSize must not exceed 100");
        }
        var result = repository.sessions(
                query.tenantId(), range.since(time.now()), status,
                (page - 1) * pageSize, pageSize);
        return new SessionsResponse(
                result.sessions().stream().map(ObservabilityApplicationService::view).toList(),
                result.total(), page, pageSize, result.runtimeActive());
    }

    @Override
    @Transactional
    public SessionView stopSession(StopSessionCommand command) {
        requireOrganizationAdmin(command.tenantId(), command.userId());
        ObservabilitySession session = repository
                .findSession(command.tenantId(), command.agentRunId())
                .orElseThrow(() -> notFound(command.agentRunId()));
        if (session.runtimeActive()) {
            runtime.cancel(new CancelAgentRunCommand(
                    session.id(), "Stopped by Organization administrator"));
        }
        return repository.findSession(command.tenantId(), command.agentRunId())
                .map(ObservabilityApplicationService::view)
                .orElseGet(() -> view(session));
    }

    @Override
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public BatchStopView stopSessions(BatchStopSessionsCommand command) {
        requireOrganizationAdmin(command.tenantId(), command.userId());
        if (command.agentRunIds().isEmpty() || command.agentRunIds().size() > 100
                || command.agentRunIds().stream().distinct().count()
                != command.agentRunIds().size()) {
            throw new IllegalArgumentException("agentRunIds must contain 1-100 unique values");
        }
        int stopped = 0;
        List<String> failed = new ArrayList<>();
        for (String id : command.agentRunIds()) {
            try {
                stopSession(new StopSessionCommand(command.tenantId(), command.userId(), id));
                stopped++;
            } catch (RuntimeException error) {
                failed.add(id);
            }
        }
        return new BatchStopView(command.agentRunIds().size(), stopped, failed);
    }

    private void requireOrganizationAdmin(String tenantId, String userId) {
        var membership = identity.findTenantMembership(tenantId, userId)
                .filter(value -> value.status() == TenantMembershipStatus.ACTIVE)
                .filter(value -> value.role() == TenantRole.OWNER
                        || value.role() == TenantRole.ADMIN)
                .orElseThrow(() -> new BusinessException(
                        "Organization administrator access is required", HttpStatus.FORBIDDEN,
                        "OBSERVABILITY_ADMIN_REQUIRED"));
        if (!membership.tenantId().equals(tenantId)) {
            throw new BusinessException("Organization scope mismatch", HttpStatus.FORBIDDEN);
        }
    }

    private static String normalizeStatus(String value) {
        String normalized = value == null || value.isBlank()
                ? "all"
                : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!List.of("all", "active", "idle", "closed").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported session status: " + value);
        }
        return normalized;
    }

    private static MonitoringRange monitoringRange(String value) {
        try {
            return MonitoringRange.fromWire(value);
        } catch (IllegalArgumentException error) {
            throw new BusinessException("Unsupported monitoring range", HttpStatus.BAD_REQUEST,
                    "OBSERVABILITY_RANGE_INVALID");
        }
    }

    private static BusinessException notFound(String id) {
        return new BusinessException(
                "Runtime session not found: " + id,
                HttpStatus.NOT_FOUND,
                "OBSERVABILITY_SESSION_NOT_FOUND");
    }

    private static UsageTimeseriesPoint view(ObservabilityTimeseriesPoint point) {
        return new UsageTimeseriesPoint(
                point.bucket(), point.inputTokens(), point.outputTokens(),
                point.cacheReadTokens(), point.cacheCreateTokens(),
                dollars(point.costMicros()), point.calls(), point.activeSessions());
    }

    private static UsageRow view(ObservabilityUsageRow row) {
        return new UsageRow(
                row.key(), row.name(), row.subLabel(), row.agentCount(), row.sessionCount(),
                row.inputTokens(), row.outputTokens(), row.cacheReadTokens(),
                row.cacheCreateTokens(), row.inputTokens() + row.outputTokens()
                        + row.cacheReadTokens() + row.cacheCreateTokens(),
                dollars(row.costMicros()), row.callCount(), row.averageLatencyMs(),
                row.incompleteCost());
    }

    private static SessionView view(ObservabilitySession session) {
        return new SessionView(
                session.id(), session.name(), session.agentId(), session.agentName(),
                session.organizationId(), session.organizationName(), session.providerName(),
                session.runtimeActive(), session.inputTokens(), session.outputTokens(),
                session.status(), session.durationSeconds(), session.lastActivityAt(),
                session.messageCount(), false, session.source(), session.closeReason(),
                session.processing(), session.createdAt(), session.updatedAt(),
                session.closedAt(), null);
    }

    private static Double dollars(Long micros) {
        return micros == null ? null : micros / 1_000_000D;
    }
}
