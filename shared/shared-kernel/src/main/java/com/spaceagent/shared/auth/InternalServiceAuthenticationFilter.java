package com.spaceagent.shared.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Validates service-to-service calls from gateway or trusted internal services.
 */
public class InternalServiceAuthenticationFilter extends OncePerRequestFilter {

    public static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";
    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String TENANT_ID_HEADER = "X-Tenant-Id";
    public static final String TENANT_ROLE_HEADER = "X-Tenant-Role";

    private final byte[] expectedToken;

    public InternalServiceAuthenticationFilter(String expectedToken) {
        if (expectedToken == null || expectedToken.isBlank()) {
            throw new IllegalStateException("Internal service token is not configured.");
        }
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        boolean internalPath = path.startsWith("/internal/") || path.startsWith("/api/v1/internal/");
        return "OPTIONS".equalsIgnoreCase(request.getMethod()) || !internalPath;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String providedToken = request.getHeader(INTERNAL_TOKEN_HEADER);
        if (providedToken == null || providedToken.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!matches(providedToken)) {
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"success\":false,\"message\":\"Invalid internal service token\"}");
            return;
        }

        String userId = request.getHeader(USER_ID_HEADER);
        String principal = userId == null || userId.isBlank() ? "internal-service" : userId;
        String tenantId = request.getHeader(TENANT_ID_HEADER);
        String tenantRole = request.getHeader(TENANT_ROLE_HEADER);
        if ((tenantId == null || tenantId.isBlank()) && userId != null && !userId.isBlank()) {
            // Compatibility for pre-tenancy internal calls and migrated personal workspaces.
            tenantId = userId;
            tenantRole = "OWNER";
        }
        var authentication = new UsernamePasswordAuthenticationToken(
                principal,
                providedToken,
                tenantAuthorities(tenantRole)
        );
        if (tenantId != null && !tenantId.isBlank()) {
            authentication.setDetails(new TenantAuthenticationDetails(tenantId, tenantRole));
        }
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
    }

    private List<SimpleGrantedAuthority> tenantAuthorities(String tenantRole) {
        if (tenantRole == null || tenantRole.isBlank()) {
            return List.of(new SimpleGrantedAuthority("ROLE_INTERNAL"));
        }
        return List.of(
                new SimpleGrantedAuthority("ROLE_INTERNAL"),
                new SimpleGrantedAuthority("ROLE_TENANT_" + tenantRole.trim().toUpperCase(java.util.Locale.ROOT))
        );
    }

    private boolean matches(String providedToken) {
        if (providedToken == null || providedToken.isBlank()) {
            return false;
        }
        byte[] provided = providedToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedToken, provided);
    }
}
