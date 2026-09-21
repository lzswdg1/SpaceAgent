package com.spaceagent.platform.identity.domain;

public record ConsumedRefreshToken(
        String userId,
        String tenantId,
        TenantRole tenantRole,
        java.util.UUID sessionId,
        long accessVersion) {
    public ConsumedRefreshToken(String userId,String tenantId,TenantRole tenantRole){this(userId,tenantId,tenantRole,java.util.UUID.randomUUID(),0);}
}
