package com.spaceagent.platform.identity.application;

import com.spaceagent.platform.identity.api.IdentityPresenceApi;
import com.spaceagent.platform.identity.domain.IdentityPresenceRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
@Transactional
public class IdentityPresenceService implements IdentityPresenceApi {
    private final IdentityPresenceRepository repository;
    private final TimeProvider time;
    public IdentityPresenceService(IdentityPresenceRepository repository, TimeProvider time) {
        this.repository = repository;
        this.time = time;
    }
    @Override public Lease heartbeat(Heartbeat command) {
        Instant now = time.now();
        String client = command.clientType() == null ? "UNKNOWN" : command.clientType();
        if (!Set.of("WEB", "CLI", "API", "UNKNOWN").contains(client))
            throw new BusinessException("Invalid client type", HttpStatus.BAD_REQUEST, "PRESENCE_CLIENT_INVALID");
        Instant until = now.plusSeconds(90);
        if (command.accessExpiresAt().isBefore(until)) until = command.accessExpiresAt();
        if (!until.isAfter(now) || !repository.heartbeat(command.sessionId(), command.userId(),
                command.tenantId(), command.accessVersion(), command.accessTokenHash(), client, now, until))
            throw new BusinessException("Session is no longer active", HttpStatus.UNAUTHORIZED, "PRESENCE_SESSION_INVALID");
        return new Lease(command.sessionId(), now, until, 30);
    }
    @Override public void leave(UUID sessionId, String userId) { repository.leave(sessionId, userId, time.now()); }
    @Override @Transactional(readOnly = true) public Summary summary() {
        Instant now = time.now();
        var counts = repository.counts(now);
        boolean observed = counts.observedSessions() > 0;
        return new Summary(observed ? counts.users() : null, observed ? counts.sessions() : null,
                counts.observedSessions(), observed ? "HEARTBEAT_CLIENTS_ONLY" : "NO_CLIENT_HEARTBEATS",
                "Distinct authorized login sessions with an unexpired server-time heartbeat lease; not all clients or physical devices",
                90, now);
    }
}
