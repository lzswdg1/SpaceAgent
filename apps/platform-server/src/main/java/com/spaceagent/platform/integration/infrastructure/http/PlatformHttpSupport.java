package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.shared.auth.TenantAuthenticationDetails;
import com.spaceagent.shared.auth.TenantSecurity;
import org.springframework.security.core.Authentication;

/**
 * Small HTTP adapter helpers. They do not contain business logic; they only
 * extract authenticated identity from the Spring Security context.
 */
final class PlatformHttpSupport {

    private PlatformHttpSupport() {
    }

    static String userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("Authentication required");
        }
        String name = authentication.getName();
        if (name == null || name.isBlank() || "anonymousUser".equals(name)) {
            throw new IllegalStateException("Authentication required");
        }
        return name;
    }

    static String tenantId(Authentication authentication) {
        if (authentication != null && authentication.getDetails() instanceof TenantAuthenticationDetails details) {
            return details.tenantId();
        }
        return userId(authentication);
    }

    static String tenantRole(Authentication authentication) {
        if (authentication != null && authentication.getDetails() instanceof TenantAuthenticationDetails details) {
            return details.tenantRole();
        }
        return "OWNER";
    }

    static void requireWrite(Authentication authentication) {
        TenantSecurity.requireWrite(authentication);
    }

    static void requireAdmin(Authentication authentication) {
        TenantSecurity.requireAdmin(authentication);
    }
}
