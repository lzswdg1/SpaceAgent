package com.spaceagent.admin.platformclient;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Component
public class AdminPlatformServiceTokenIssuer {
    private final AdminPlatformClientProperties properties;
    private final Clock clock;
    private final SecretKey key;

    public AdminPlatformServiceTokenIssuer(AdminPlatformClientProperties properties, Clock adminClock) {
        if (properties.getJwtSecret() == null || properties.getJwtSecret().length() < 32) {
            throw new IllegalStateException("admin.platform-client.jwt-secret must contain at least 32 characters");
        }
        if (properties.getTokenSeconds() < 1 || properties.getTokenSeconds() > 60) {
            throw new IllegalStateException("Admin platform service JWT lifetime must be between 1 and 60 seconds");
        }
        this.properties = properties;
        this.clock = adminClock;
        this.key = Keys.hmacShaKeyFor(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String issue(String actorId, String scope, String requestId) {
        return issue(actorId, scope, requestId, null);
    }

    public String issue(String actorId, String scope, String requestId, UUID commandId) {
        Instant now = clock.instant();
        var builder = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(properties.getIssuer())
                .audience().add(properties.getAudience()).and()
                .subject(properties.getServiceId())
                .claim("actor_id", actorId)
                .claim("scope", scope)
                .claim("request_id", requestId);
        if (commandId != null) builder.claim("command_id", commandId.toString());
        return builder
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(properties.getTokenSeconds())))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
