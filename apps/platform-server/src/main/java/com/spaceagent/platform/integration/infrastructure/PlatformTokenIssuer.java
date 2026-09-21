package com.spaceagent.platform.integration.infrastructure;

import com.spaceagent.platform.identity.api.AuthenticatedIdentityView;
import com.spaceagent.platform.identity.api.IdentitySessionApplicationApi;
import com.spaceagent.platform.identity.api.IdentitySessionView;
import com.spaceagent.shared.auth.AuthToken;
import com.spaceagent.shared.exception.BusinessException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.http.HttpStatus;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * Integration-side JWT issuer for the platform-server HTTP edge.
 *
 * <p>Identity returns durable facts; this adapter turns those facts into the same
 * bearer-token contract consumed by the existing clients and by the shared
 * {@link com.spaceagent.shared.auth.JwtAuthenticationFilter}.
 */
public class PlatformTokenIssuer {

    private final PlatformSecurityProperties properties;
    private final IdentitySessionApplicationApi sessionApi;
    private final SecretKey key;

    public PlatformTokenIssuer(
            PlatformSecurityProperties properties,
            IdentitySessionApplicationApi sessionApi) {
        if (properties.getJwtSecret() == null || properties.getJwtSecret().length() < 32) {
            throw new IllegalStateException("platform.security.jwt-secret must be at least 32 characters");
        }
        this.properties = properties;
        this.sessionApi = sessionApi;
        this.key = Keys.hmacShaKeyFor(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public AuthToken issue(AuthenticatedIdentityView identity) {
        return issue(sessionApi.openSession(identity));
    }

    public AuthToken issue(IdentitySessionView session) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getJwtExpirationHours(), ChronoUnit.HOURS);
        String token = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(session.userId())
                .claim("username", session.username())
                .claim("role", "USER")
                .claim("tenant_id", session.tenantId())
                .claim("organization_id", session.tenantId())
                .claim("tenant_role", session.tenantRole())
                .claim("session_id", session.sessionId() == null ? null : session.sessionId().toString())
                .claim("access_version", session.accessVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
        return new AuthToken(
                token,
                session.refreshToken(),
                session.userId(),
                session.username(),
                "USER",
                session.tenantId(),
                session.tenantRole(),
                expiresAt,
                session.refreshExpiresAt());
    }

    public AuthToken refresh(String refreshToken) {
        return issue(sessionApi.rotateRefreshToken(refreshToken));
    }

    public AuthToken switchOrganization(String userId, String organizationId) {
        return issue(sessionApi.openSessionForOrganization(userId, organizationId));
    }

    public void logout(String userId, String accessToken, String refreshToken) {
        if (accessToken == null || accessToken.isBlank()) {
            sessionApi.revokeRefreshToken(refreshToken);
            return;
        }
        try {
            Claims claims = parse(accessToken);
            if (!userId.equals(claims.getSubject())) {
                throw new BusinessException(
                        "Token does not belong to current user",
                        HttpStatus.UNAUTHORIZED,
                        "TOKEN_SUBJECT_MISMATCH");
            }
            Instant expiresAt = claims.getExpiration() == null
                    ? Instant.now()
                    : claims.getExpiration().toInstant();
            String sessionValue = claims.get("session_id", String.class);
            UUID sessionId = sessionValue == null || sessionValue.isBlank()
                    ? null : UUID.fromString(sessionValue);
            sessionApi.revokeSession(new IdentitySessionApplicationApi.SessionRevocationCommand(
                    userId, sessionId, accessToken, expiresAt, refreshToken));
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BusinessException(
                    "Invalid authorization token",
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_ACCESS_TOKEN");
        }
    }

    public boolean isRevoked(String accessToken) {
        try {
            Claims claims = parse(accessToken);
            Number version = claims.get("access_version", Number.class);
            long accessVersion = version == null ? 0 : version.longValue();
            if (sessionApi.isAccessTokenRevoked(accessToken)) return true;
            String sessionValue = claims.get("session_id", String.class);
            return sessionValue == null || sessionValue.isBlank()
                    ? !sessionApi.isAccessVersionCurrent(claims.getSubject(), accessVersion)
                    : !sessionApi.isSessionActive(
                            claims.getSubject(), UUID.fromString(sessionValue), accessVersion);
        } catch (JwtException | IllegalArgumentException ex) {
            return true;
        }
    }

    public AccessContext accessContext(String token) {
        Claims claims = parse(token);
        String sessionId = claims.get("session_id", String.class);
        if (sessionId == null) {
            throw new BusinessException("Sign in again to establish presence", HttpStatus.UNAUTHORIZED,
                    "SESSION_REAUTHENTICATION_REQUIRED");
        }
        Number version = claims.get("access_version", Number.class);
        return new AccessContext(UUID.fromString(sessionId), claims.getSubject(),
                claims.get("tenant_id", String.class), version == null ? 0 : version.longValue(),
                claims.getExpiration().toInstant());
    }

    public record AccessContext(UUID sessionId, String userId, String tenantId, long accessVersion,
                                Instant expiresAt) { }

    private Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
