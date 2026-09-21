package com.spaceagent.platform.identity;

import com.spaceagent.platform.identity.application.IdentityPresenceService;
import com.spaceagent.platform.identity.application.IdentityPresenceMaintenanceWorker;
import com.spaceagent.platform.identity.api.IdentityPresenceApi;
import com.spaceagent.platform.identity.domain.IdentityPresenceRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class IdentityPresenceServiceTest {
    @Test void noClientIntegrationIsUnknownNotZeroAndPartialCoverageRemainsExplicit() {
        Instant now = Instant.parse("2026-09-13T10:00:00Z");
        var repository = mock(IdentityPresenceRepository.class);
        var service = new IdentityPresenceService(repository, () -> now);
        when(repository.counts(now)).thenReturn(new IdentityPresenceRepository.Counts(0,0,0));
        assertThat(service.summary().onlineUsers()).isNull();
        assertThat(service.summary().onlineSessions()).isNull();
        assertThat(service.summary().coverage()).isEqualTo("NO_CLIENT_HEARTBEATS");
        when(repository.counts(now)).thenReturn(new IdentityPresenceRepository.Counts(1,2,3));
        assertThat(service.summary().onlineUsers()).isEqualTo(1);
        assertThat(service.summary().onlineSessions()).isEqualTo(2);
        assertThat(service.summary().coverage()).isEqualTo("HEARTBEAT_CLIENTS_ONLY");
    }
    @Test void leaseCannotOutliveAccessTokenAndRepositoryDenialIsNotSuccess() {
        Instant now = Instant.parse("2026-09-13T10:00:00Z");
        var repository = mock(IdentityPresenceRepository.class);
        var service = new IdentityPresenceService(repository, () -> now);
        var command = new IdentityPresenceApi.Heartbeat(UUID.randomUUID(), "user", "org", 2,
                "a".repeat(64), "WEB", now.plusSeconds(10));
        when(repository.heartbeat(any(),any(),any(),anyLong(),any(),any(),any(),any())).thenReturn(true);
        assertThat(service.heartbeat(command).expiresAt()).isEqualTo(now.plusSeconds(10));
        verify(repository).heartbeat(command.sessionId(), "user", "org", 2, "a".repeat(64), "WEB", now, now.plusSeconds(10));
        when(repository.heartbeat(any(),any(),any(),anyLong(),any(),any(),any(),any())).thenReturn(false);
        assertThatThrownBy(() -> service.heartbeat(command)).hasMessageContaining("no longer active");
    }
    @Test void maintenanceDeletesOnlyBoundedOldExpiredLeaseRows() {
        Instant now = Instant.parse("2026-09-13T10:00:00Z");
        var repository = mock(IdentityPresenceRepository.class);
        var worker = new IdentityPresenceMaintenanceWorker(repository, () -> now, 24, 1000);
        worker.poll();
        verify(repository).sweepExpired(now.minusSeconds(24 * 3600), 1000);
    }
}
