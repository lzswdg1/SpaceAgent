package com.spaceagent.platform.integration.infrastructure;

import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.api.IdentityActivityApplicationApi;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantStatus;
import com.spaceagent.shared.auth.TenantAuthenticationDetails;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Enforces durable access-token revocation and tenant membership after JWT parsing.
 */
public class PlatformIdentityAuthorizationFilter extends OncePerRequestFilter {

    private final PlatformTokenIssuer tokenIssuer;
    private final IdentityApplicationApi identityApi;
    private final IdentityActivityApplicationApi activityApi;

    public PlatformIdentityAuthorizationFilter(
            PlatformTokenIssuer tokenIssuer,
            IdentityApplicationApi identityApi,
            IdentityActivityApplicationApi activityApi) {
        this.tokenIssuer = tokenIssuer;
        this.identityApi = identityApi;
        this.activityApi = activityApi;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || isInternal(authentication)) {
            filterChain.doFilter(request, response);
            return;
        }

        Object credentials = authentication.getCredentials();
        if (credentials instanceof String accessToken && tokenIssuer.isRevoked(accessToken)) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "ACCESS_TOKEN_REVOKED", "Access token has been revoked");
            return;
        }

        if (!activityApi.isUserActive(authentication.getName())) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED,
                    "USER_NOT_ACTIVE", "User account is not active");
            return;
        }

        if (authentication.getDetails() instanceof TenantAuthenticationDetails details) {
            var tenant = identityApi.findTenant(details.tenantId())
                    .filter(value -> value.status() == TenantStatus.ACTIVE);
            var membership = identityApi.findTenantMembership(
                            details.tenantId(), authentication.getName())
                    .filter(value -> value.status() == TenantMembershipStatus.ACTIVE);
            if (tenant.isEmpty() || membership.isEmpty()) {
                reject(response, HttpServletResponse.SC_FORBIDDEN,
                        "TENANT_MEMBERSHIP_REQUIRED", "User is not an active member of tenant");
                return;
            }
            if (!membership.orElseThrow().role().name().equals(details.tenantRole())) {
                reject(response, HttpServletResponse.SC_UNAUTHORIZED,
                        "TENANT_ROLE_CHANGED",
                        "Organization role changed; switch Organization to refresh the session");
                return;
            }
        }
        activityApi.markSeen(authentication.getName(), request.getRemoteAddr(),
                request.getHeader("User-Agent"), clientType(request));
        filterChain.doFilter(request, response);
    }

    private String clientType(HttpServletRequest request) {
        String declared = request.getHeader("X-SpaceAgent-Client");
        if (declared != null && !declared.isBlank()) return declared;
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && userAgent.contains("Mozilla/")) return "WEB";
        return "API";
    }

    private boolean isInternal(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_INTERNAL".equals(authority.getAuthority())
                        || "ROLE_SYSTEM_ADMIN_INTERNAL".equals(authority.getAuthority()));
    }

    private void reject(HttpServletResponse response, int status, String code, String message) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"success\":false,\"code\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
