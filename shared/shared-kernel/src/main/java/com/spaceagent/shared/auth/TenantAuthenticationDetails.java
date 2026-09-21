package com.spaceagent.shared.auth;

import java.io.Serializable;
import java.util.Locale;

public record TenantAuthenticationDetails(String tenantId, String tenantRole) implements Serializable {

    public TenantAuthenticationDetails {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        tenantId = tenantId.trim();
        tenantRole = tenantRole == null || tenantRole.isBlank()
                ? "MEMBER"
                : tenantRole.trim().toUpperCase(Locale.ROOT);
    }
}
