package com.spaceagent.platform.identity.domain;

import java.time.Instant;

/**
 * Durable refresh-token session. Only a SHA-256 digest is persisted.
 */
public record RefreshTokenSession(
        String tokenHash,
        String userId,
        String tenantId,
        TenantRole tenantRole,
        Instant expiresAt,
        Instant createdAt,
        java.util.UUID sessionId,
        long accessVersion) {
    public RefreshTokenSession(String tokenHash,String userId,String tenantId,TenantRole tenantRole,
            Instant expiresAt,Instant createdAt){this(tokenHash,userId,tenantId,tenantRole,expiresAt,createdAt,java.util.UUID.randomUUID(),0);}
}
