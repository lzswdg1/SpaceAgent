package com.spaceagent.platform.identity.domain;

/**
 * Lifecycle state of a tenant.
 */
public enum TenantStatus {
    ACTIVE,
    SUSPENDED,
    DELETING,
    DELETED
}
