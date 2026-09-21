package com.spaceagent.admin.dashboard;

import com.spaceagent.admin.audit.application.AdminAuditService;
import com.spaceagent.admin.platformclient.AdminPlatformClient;
import com.spaceagent.admin.platformclient.AdminPlatformClientException;
import com.spaceagent.admin.platformclient.PlatformAdminWire;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminPlatformReadServiceTest {
    @Test
    void resourceReadFailureIsAuditedAndNeverReplacedByZeroOrCachedMetrics() {
        var client = mock(AdminPlatformClient.class);
        var audit = mock(AdminAuditService.class);
        UUID actor = UUID.randomUUID(), session = UUID.randomUUID();
        var filters = java.util.Map.of("userId", "fixture-user");
        when(client.resourceObservations(filters, actor.toString(), "resource-request"))
                .thenThrow(new AdminPlatformClientException("PLATFORM_UNAVAILABLE", "down"));
        var service = new AdminPlatformReadService(client, audit, Clock.systemUTC());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.resourceObservations(filters, actor, session, "resource-request"))
                .isInstanceOf(com.spaceagent.admin.shared.AdminApiException.class);
        verify(audit).append(org.mockito.ArgumentMatchers.eq(actor), org.mockito.ArgumentMatchers.eq(session),
                org.mockito.ArgumentMatchers.eq("ADMIN_RESOURCE_OBSERVATIONS_READ"), org.mockito.ArgumentMatchers.eq("RESOURCE"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq("resource-request"), anyString(),
                org.mockito.ArgumentMatchers.eq(com.spaceagent.admin.audit.domain.AdminAuditOutcome.FAILED),
                org.mockito.ArgumentMatchers.eq("PLATFORM_UNAVAILABLE"));
    }

    @Test
    void servesRecentOverviewAsExplicitlyStaleWhenPlatformBecomesUnavailable() {
        AdminPlatformClient client = mock(AdminPlatformClient.class);
        AdminAuditService audit = mock(AdminAuditService.class);
        PlatformAdminWire.Overview overview = overview();
        when(client.overview(anyString(), anyString(), anyString()))
                .thenReturn(overview)
                .thenThrow(new AdminPlatformClientException("PLATFORM_UNAVAILABLE", "down"));
        AdminPlatformReadService service = new AdminPlatformReadService(client, audit,
                Clock.fixed(Instant.parse("2026-08-26T08:00:00Z"), ZoneOffset.UTC));
        UUID actor = UUID.randomUUID();
        UUID session = UUID.randomUUID();

        assertThat(service.dashboard("24h", actor, session, "request-1").stale()).isFalse();
        var stale = service.dashboard("24h", actor, session, "request-2");

        assertThat(stale.stale()).isTrue();
        assertThat(stale.staleReasonCode()).isEqualTo("PLATFORM_UNAVAILABLE");
        assertThat(stale.projection()).isSameAs(overview);
    }

    @Test void presenceKeepsUnavailableCountsNullAndAuditsReads() {
        var client = mock(AdminPlatformClient.class);
        var audit = mock(AdminAuditService.class);
        var expected = new PlatformAdminWire.PresenceSummary(null, null, 0, "NO_CLIENT_HEARTBEATS",
                "Heartbeat sessions only", 90, Instant.now());
        when(client.presence(anyString(), anyString())).thenReturn(expected);
        UUID actor = UUID.randomUUID(), session = UUID.randomUUID();
        var service = new AdminPlatformReadService(client, audit, Clock.systemUTC());
        assertThat(service.presence(actor, session, "presence-request")).isEqualTo(expected);
        verify(audit).append(org.mockito.ArgumentMatchers.eq(actor), org.mockito.ArgumentMatchers.eq(session),
                org.mockito.ArgumentMatchers.eq("ADMIN_PRESENCE_READ"), org.mockito.ArgumentMatchers.eq("PLATFORM"),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.eq("presence-request"),
                anyString(), org.mockito.ArgumentMatchers.eq(com.spaceagent.admin.audit.domain.AdminAuditOutcome.SUCCEEDED),
                org.mockito.ArgumentMatchers.isNull());
    }

    private static PlatformAdminWire.Overview overview() {
        return new PlatformAdminWire.Overview("24h", Instant.parse("2026-08-26T08:00:00Z"),
                "test", 1038, "UP",
                new PlatformAdminWire.IdentityOverview(1, 0, 1, 0, 0, 0, 1, 1, 1, 1, 1, 0),
                new PlatformAdminWire.InferenceOverview(0, 0, 0, 0, 0, 0, 0),
                new PlatformAdminWire.AgentOverview(0, 0, 0),
                new PlatformAdminWire.ProjectOverview(0, 0, 0, 0),
                new PlatformAdminWire.ConversationOverview(0, 0, 0),
                new PlatformAdminWire.RuntimeOverview(0, 0, 0, 0, 0, 0, 0),
                new PlatformAdminWire.ToolingOverview(0, 0, 0));
    }
}
