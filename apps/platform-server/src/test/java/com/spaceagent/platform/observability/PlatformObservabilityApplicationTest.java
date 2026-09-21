package com.spaceagent.platform.observability;

import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.api.TenantMembershipView;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.observability.api.ObservabilityApplicationApi;
import com.spaceagent.platform.observability.application.ObservabilityApplicationService;
import com.spaceagent.platform.observability.domain.ObservabilityOverview;
import com.spaceagent.platform.observability.domain.MonitoringRange;
import com.spaceagent.platform.observability.domain.ObservabilityQueryRepository;
import com.spaceagent.platform.observability.domain.ObservabilitySession;
import com.spaceagent.platform.observability.domain.ObservabilityTimeseriesPoint;
import com.spaceagent.platform.observability.domain.ObservabilityUsageRow;
import com.spaceagent.platform.observability.domain.UsageDimension;
import com.spaceagent.platform.runtime.api.CancelAgentRunCommand;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformObservabilityApplicationTest {

    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");

    @Test
    void rollingDayAndAllTimeAreDistinctFromUtcToday() {
        assertThat(MonitoringRange.fromWire("1d").since(NOW))
                .isEqualTo(NOW.minusSeconds(86400));
        assertThat(MonitoringRange.fromWire("today").since(NOW))
                .isEqualTo(Instant.parse("2026-08-23T00:00:00Z"));
        assertThat(MonitoringRange.fromWire("all").since(NOW)).isNull();
        assertThatThrownBy(() -> MonitoringRange.fromWire("forever"))
                .isInstanceOf(IllegalArgumentException.class);
        var repository = mock(ObservabilityQueryRepository.class);
        when(repository.usage("tenant", null, UsageDimension.AGENT, 201)).thenReturn(List.of(
                new ObservabilityUsageRow("a", "Agent", null, null, 1, 100, 50, 0, 0, 1200000L, 1, 12, false)));
        var service = new ObservabilityApplicationService(repository, identity(TenantRole.OWNER),
                mock(RuntimeApplicationApi.class), () -> NOW);
        var result = service.usage(new ObservabilityApplicationApi.UsageQuery("tenant", "owner", "all", "agent"));
        assertThat(result.totalCostUsd()).isEqualTo(1.2);
        assertThat(result.range()).isEqualTo("all");
        verify(repository).usage("tenant", null, UsageDimension.AGENT, 201);
    }

    @Test
    void organizationOwnerReadsContractCompatibleOverviewAndUsage() {
        ObservabilityQueryRepository repository = mock(ObservabilityQueryRepository.class);
        IdentityApplicationApi identity = identity(TenantRole.OWNER);
        when(repository.overview("tenant", NOW.minusSeconds(7 * 86400L), "day"))
                .thenReturn(new ObservabilityOverview(
                        1, 2, 3, 1, 1, 1, 1,
                        100, 50, 10, 5, null, 2, true));
        when(repository.timeseries("tenant", NOW.minusSeconds(7 * 86400L), "day"))
                .thenReturn(List.of(new ObservabilityTimeseriesPoint(
                        NOW, 100, 50, 10, 5, null, 2, 1)));
        when(repository.usage(
                "tenant", NOW.minusSeconds(7 * 86400L), UsageDimension.AGENT, 201))
                .thenReturn(List.of(new ObservabilityUsageRow(
                        "agent", "Agent", null, null, 1,
                        100, 50, 10, 5, null, 2, 12.5, true)));
        var service = new ObservabilityApplicationService(
                repository, identity, mock(RuntimeApplicationApi.class), () -> NOW);

        var overview = service.overview(
                new ObservabilityApplicationApi.MonitoringQuery("tenant", "owner", "7d"));
        assertThat(overview.overview().totalAgents()).isEqualTo(2);
        assertThat(overview.overview().totalCostUsd()).isNull();
        assertThat(overview.overview().hasIncompleteCost()).isTrue();
        assertThat(overview.timeseries()).singleElement().satisfies(point ->
                assertThat(point.calls()).isEqualTo(2));

        var usage = service.usage(
                new ObservabilityApplicationApi.UsageQuery(
                        "tenant", "owner", "7d", "agent"));
        assertThat(usage.rows()).singleElement().satisfies(row -> {
            assertThat(row.totalTokens()).isEqualTo(165);
            assertThat(row.costUsd()).isNull();
        });
    }

    @Test
    void ordinaryMemberCannotReadOrganizationOperations() {
        var service = new ObservabilityApplicationService(
                mock(ObservabilityQueryRepository.class),
                identity(TenantRole.MEMBER),
                mock(RuntimeApplicationApi.class),
                () -> NOW);

        assertThatThrownBy(() -> service.overview(
                new ObservabilityApplicationApi.MonitoringQuery(
                        "tenant", "member", "today")))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getStatus()).isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN);
                    assertThat(error.getCode()).isEqualTo("OBSERVABILITY_ADMIN_REQUIRED");
                });
    }

    @Test
    void stopValidatesTenantProjectionAndDelegatesToRuntimeApi() {
        ObservabilityQueryRepository repository = mock(ObservabilityQueryRepository.class);
        RuntimeApplicationApi runtime = mock(RuntimeApplicationApi.class);
        ObservabilitySession active = session("run", true, "active");
        ObservabilitySession closed = session("run", false, "closed");
        when(repository.findSession("tenant", "run"))
                .thenReturn(Optional.of(active), Optional.of(closed));
        var service = new ObservabilityApplicationService(
                repository, identity(TenantRole.ADMIN), runtime, () -> NOW);

        var stopped = service.stopSession(
                new ObservabilityApplicationApi.StopSessionCommand(
                        "tenant", "admin", "run"));
        assertThat(stopped.status()).isEqualTo("closed");
        verify(runtime).cancel(new CancelAgentRunCommand(
                "run", "Stopped by Organization administrator"));
    }

    private IdentityApplicationApi identity(TenantRole role) {
        IdentityApplicationApi identity = mock(IdentityApplicationApi.class);
        when(identity.findTenantMembership("tenant", role == TenantRole.MEMBER ? "member"
                : role == TenantRole.ADMIN ? "admin" : "owner"))
                .thenReturn(Optional.of(new TenantMembershipView(
                        "tenant", role == TenantRole.MEMBER ? "member"
                                : role == TenantRole.ADMIN ? "admin" : "owner",
                        role, TenantMembershipStatus.ACTIVE, NOW, NOW)));
        return identity;
    }

    private ObservabilitySession session(String id, boolean active, String status) {
        return new ObservabilitySession(
                id, "Conversation", "agent", "Agent", "tenant", "Tenant", "Provider",
                active, 10, 5, status, 1, NOW, 2, active, NOW, NOW,
                active ? null : NOW, active ? null : "stopped", "chat");
    }
}
