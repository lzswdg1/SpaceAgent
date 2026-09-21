package com.spaceagent.admin.security;

import com.spaceagent.admin.identity.domain.AdminIdentityRepository;
import com.spaceagent.admin.identity.domain.AdminSession;
import com.spaceagent.admin.identity.domain.SystemAdministrator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.UUID;

public class AdminSessionAuthorizationFilter extends OncePerRequestFilter {
    private final AdminIdentityRepository repository;
    private final Clock clock;

    public AdminSessionAuthorizationFilter(AdminIdentityRepository repository, Clock adminClock) {
        this.repository = repository;
        this.clock = adminClock;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (!(current instanceof JwtAuthenticationToken token) || !current.isAuthenticated()) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            UUID principalId = UUID.fromString(token.getToken().getSubject());
            UUID sessionId = UUID.fromString(token.getToken().getClaimAsString("session_id"));
            Number credentialVersion = token.getToken().getClaim("credential_version");
            AdminSession session = repository.findSessionById(sessionId).orElse(null);
            SystemAdministrator administrator = repository.findPrincipalById(principalId).orElse(null);
            boolean valid = session != null && administrator != null
                    && session.principalId().equals(principalId)
                    && session.activeAt(clock.instant())
                    && administrator.canAuthenticate()
                    && credentialVersion != null
                    && session.credentialVersion() == credentialVersion.longValue()
                    && administrator.credentialVersion() == credentialVersion.longValue();
            if (!valid) {
                reject(response, HttpServletResponse.SC_UNAUTHORIZED, "ADMIN_INVALID_SESSION",
                        "Administrator session is invalid");
                return;
            }
            if (administrator.mustChangePassword() && !passwordChangeAllowed(request)) {
                reject(response, HttpServletResponse.SC_FORBIDDEN,
                        "ADMIN_PASSWORD_CHANGE_REQUIRED",
                        "Administrator password must be changed before continuing");
                return;
            }
            filterChain.doFilter(request, response);
        } catch (RuntimeException invalidClaims) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "ADMIN_INVALID_SESSION",
                    "Administrator session is invalid");
        }
    }

    private static boolean passwordChangeAllowed(HttpServletRequest request) {
        String path = request.getRequestURI();
        return ("GET".equalsIgnoreCase(request.getMethod()) && path.equals("/admin/v1/me"))
                || ("POST".equalsIgnoreCase(request.getMethod())
                && (path.equals("/admin/v1/auth/password")
                || path.equals("/admin/v1/auth/logout")));
    }

    private static void reject(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"code\":\"" + code
                + "\",\"message\":\"" + message + "\"}");
    }
}
