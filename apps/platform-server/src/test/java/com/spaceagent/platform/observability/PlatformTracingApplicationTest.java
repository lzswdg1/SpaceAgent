package com.spaceagent.platform.observability;

import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.api.TenantMembershipView;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.observability.api.TracingApplicationApi;
import com.spaceagent.platform.observability.application.TracingApplicationService;
import com.spaceagent.platform.observability.domain.TraceDetail;
import com.spaceagent.platform.observability.domain.TracePage;
import com.spaceagent.platform.observability.domain.TraceSpan;
import com.spaceagent.platform.observability.domain.TraceSpanType;
import com.spaceagent.platform.observability.domain.TraceStats;
import com.spaceagent.platform.observability.domain.TraceStatus;
import com.spaceagent.platform.observability.domain.TraceSummary;
import com.spaceagent.platform.observability.domain.TracingQueryRepository;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlatformTracingApplicationTest {

    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");

    @Test
    void activeMemberReadsContractCompatibleTraceListDetailAndStats() {
        TracingQueryRepository repository = mock(TracingQueryRepository.class);
        TraceSummary trace = trace();
        TraceSpan root = span();
        when(repository.search(any(), anyInt(), anyInt()))
                .thenReturn(new TracePage(List.of(trace), 1, 25, 0));
        when(repository.find("tenant", "owner", trace.id()))
                .thenReturn(Optional.of(new TraceDetail(trace, List.of(root))));
        when(repository.stats(any())).thenReturn(new TraceStats(
                1, 1, 0, 0, 250, 250, 250, 0, 15, null));
        var service = new TracingApplicationService(
                repository, identity("owner", TenantMembershipStatus.ACTIVE), () -> NOW);

        var list = service.list(new TracingApplicationApi.TraceQuery(
                "tenant", "owner", "success", null, null, "Agent",
                100L, 500L, NOW.minusSeconds(60), NOW, 25, 0));
        assertThat(list.traces()).singleElement().satisfies(value -> {
            assertThat(value.status()).isEqualTo("success");
            assertThat(value.costUsd()).isNull();
            assertThat(value.firstTokenMs()).isNull();
            assertThat(value.metadata()).containsEntry("source", "chat");
        });
        assertThat(service.get(new TracingApplicationApi.TraceByIdQuery(
                "tenant", "owner", trace.id())).spans()).hasSize(1);
        assertThat(service.stats(new TracingApplicationApi.TraceStatsQuery(
                "tenant", "owner", null, null, null, null,
                null, null, null, null, "7d")).totalCostUsd()).isNull();
    }

    @Test
    void inactiveMembershipAndForeignTraceAreHidden() {
        var denied = new TracingApplicationService(
                mock(TracingQueryRepository.class),
                identity("owner", TenantMembershipStatus.SUSPENDED), () -> NOW);
        assertThatThrownBy(() -> denied.list(new TracingApplicationApi.TraceQuery(
                "tenant", "owner", null, null, null, null,
                null, null, null, null, 25, 0)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("TRACING_MEMBERSHIP_REQUIRED"));

        TracingQueryRepository repository = mock(TracingQueryRepository.class);
        when(repository.find("tenant", "owner", trace().id())).thenReturn(Optional.empty());
        var service = new TracingApplicationService(
                repository, identity("owner", TenantMembershipStatus.ACTIVE), () -> NOW);
        assertThatThrownBy(() -> service.get(new TracingApplicationApi.TraceByIdQuery(
                "tenant", "owner", trace().id())))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("TRACE_NOT_FOUND"));
    }

    @Test
    void invalidFiltersFailBeforeRepositoryQuery() {
        var service = new TracingApplicationService(
                mock(TracingQueryRepository.class),
                identity("owner", TenantMembershipStatus.ACTIVE), () -> NOW);
        assertThatThrownBy(() -> service.list(new TracingApplicationApi.TraceQuery(
                "tenant", "owner", "unsupported", null, null, null,
                null, null, null, null, 25, 0)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("TRACE_QUERY_INVALID"));
        assertThatThrownBy(() -> service.list(new TracingApplicationApi.TraceQuery(
                "tenant", "owner", null, null, null, null,
                500L, 100L, null, null, 25, 0)))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("TRACE_QUERY_INVALID"));
    }

    private IdentityApplicationApi identity(String userId, TenantMembershipStatus status) {
        IdentityApplicationApi identity = mock(IdentityApplicationApi.class);
        when(identity.findTenantMembership("tenant", userId))
                .thenReturn(Optional.of(new TenantMembershipView(
                        "tenant", userId, TenantRole.MEMBER, status, NOW, NOW)));
        return identity;
    }

    private TraceSummary trace() {
        return new TraceSummary(
                "00000000-0000-4000-8000-000000000001", "tenant",
                "00000000-0000-4000-8000-000000000002", "Session",
                "00000000-0000-4000-8000-000000000003", "Agent", "owner",
                "00000000-0000-4000-8000-000000000004", TraceStatus.SUCCESS,
                false, null, NOW.minusMillis(250), NOW, 250L, null,
                200, 50, 50, 3, 1, 1, 10, 5, 15,
                0, 0, null, false, Map.of("source", "chat"),
                NOW.minusMillis(250), NOW);
    }

    private TraceSpan span() {
        TraceSummary trace = trace();
        return new TraceSpan(
                trace.rootSpanId(), trace.id(), null, TraceSpanType.ROOT, "agent-run",
                TraceStatus.SUCCESS, false, null, trace.startTime(), trace.endTime(),
                trace.durationMs(), null, 10L, 5L, 15L, 0L, 0L,
                null, null, trace.metadata(), trace.createdAt(), trace.updatedAt());
    }
}
