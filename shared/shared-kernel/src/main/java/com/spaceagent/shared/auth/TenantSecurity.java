package com.spaceagent.shared.auth;

import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;

import java.util.Set;

public final class TenantSecurity {

    private static final Set<String> WRITE_ROLES = Set.of("OWNER", "ADMIN", "MEMBER");
    private static final Set<String> ADMIN_ROLES = Set.of("OWNER", "ADMIN");

    private TenantSecurity() {
    }

    public static String requireTenantId(Authentication authentication) {
        return requireDetails(authentication).tenantId();
    }

    public static String tenantRole(Authentication authentication) {
        return requireDetails(authentication).tenantRole();
    }

    public static void requireWrite(Authentication authentication) {
        requireRole(authentication, WRITE_ROLES, "Tenant write permission is required");
    }

    public static void requireAdmin(Authentication authentication) {
        requireRole(authentication, ADMIN_ROLES, "Tenant administrator permission is required");
    }

    private static void requireRole(Authentication authentication, Set<String> accepted, String message) {
        if (!accepted.contains(tenantRole(authentication))) {
            throw new BusinessException(message, HttpStatus.FORBIDDEN, "TENANT_PERMISSION_DENIED");
        }
    }

    private static TenantAuthenticationDetails requireDetails(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw missingContext();
        }
        if (authentication.getDetails() instanceof TenantAuthenticationDetails details) {
            return details;
        }
        String userId = authentication.getName();
        if (userId == null || userId.isBlank() || "anonymousUser".equals(userId)) {
            throw missingContext();
        }
        // Backward compatibility for authenticated personal-workspace calls created before tenant claims existed.
        return new TenantAuthenticationDetails(userId, "OWNER");
    }

    private static BusinessException missingContext() {
        return new BusinessException(
                "Tenant context is required",
                HttpStatus.UNAUTHORIZED,
                "TENANT_CONTEXT_REQUIRED"
        );
    }
}
