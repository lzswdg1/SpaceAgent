package com.spaceagent.platform.identity.api;

/**
 * Public identity ownership/tenant membership port exposed to other modules.
 */
public interface IdentityOwnershipPort {
    boolean isMemberOfTenant(String tenantId, String principalId);
}
