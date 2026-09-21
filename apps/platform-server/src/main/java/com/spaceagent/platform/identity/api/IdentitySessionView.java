package com.spaceagent.platform.identity.api;

import java.time.Instant;

/**
 * Authenticated identity plus its durable opaque refresh session.
 *
 * <p>The access JWT remains an integration concern. The identity module owns
 * refresh rotation and the tenant-scoped session facts used to issue it.
 */
public record IdentitySessionView(
        String userId,
        String tenantId,
        String username,
        String displayName,
        String tenantRole,
        String refreshToken,
        Instant refreshExpiresAt,
        java.util.UUID sessionId,
        long accessVersion) {
    public IdentitySessionView(String userId,String tenantId,String username,String displayName,
            String tenantRole,String refreshToken,Instant refreshExpiresAt){
        this(userId,tenantId,username,displayName,tenantRole,refreshToken,refreshExpiresAt,null,0);
    }
}
