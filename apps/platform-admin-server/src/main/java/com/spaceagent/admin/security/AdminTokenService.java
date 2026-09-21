package com.spaceagent.admin.security;

import com.spaceagent.admin.config.AdminSecurityProperties;
import com.spaceagent.admin.identity.domain.SystemAdministrator;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

public class AdminTokenService {
    private final AdminSecurityProperties properties;
    private final Clock clock;
    private final SecretKey key;

    public AdminTokenService(AdminSecurityProperties properties, Clock clock) {
        if (properties.getJwtSecret() == null || properties.getJwtSecret().length() < 32) {
            throw new IllegalStateException("admin.security.jwt-secret must contain at least 32 characters");
        }
        this.properties = properties;
        this.clock = clock;
        this.key = Keys.hmacShaKeyFor(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public AdminAccessToken issue(SystemAdministrator administrator, UUID sessionId) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.getAccessTokenMinutes(), ChronoUnit.MINUTES);
        String value = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .issuer(properties.getIssuer())
                .audience().add(properties.getAudience()).and()
                .subject(administrator.id().toString())
                .claim("roles", List.of(administrator.role().name()))
                .claim("session_id", sessionId.toString())
                .claim("credential_version", administrator.credentialVersion())
                .claim("amr", List.of("pwd"))
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new AdminAccessToken(value, expiresAt);
    }
}
