package com.spaceagent.platform.integration.infrastructure;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class SystemAdminInternalAuthenticationFilter extends OncePerRequestFilter {
    private static final String PATH_PREFIX = "/internal/system-admin/v1/";
    private final SystemAdminSecurityProperties properties;
    private final SecretKey key;

    public SystemAdminInternalAuthenticationFilter(SystemAdminSecurityProperties properties) {
        if (properties.getJwtSecret() == null || properties.getJwtSecret().length() < 32) {
            throw new IllegalStateException(
                    "platform.system-admin.security.jwt-secret must contain at least 32 characters");
        }
        if (properties.getMaximumTokenSeconds() < 1 || properties.getMaximumTokenSeconds() > 60) {
            throw new IllegalStateException("System Admin service JWT lifetime must be between 1 and 60 seconds");
        }
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.isAllowInsecureLocal() && !hasClientCertificate(request)) {
            reject(response, 401, "SYSTEM_ADMIN_MTLS_REQUIRED", "System administration mTLS is required");
            return;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            reject(response, 401, "SYSTEM_ADMIN_TOKEN_REQUIRED", "System administration token is required");
            return;
        }
        try {
            Jws<Claims> parsed = Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(authorization.substring(7));
            Claims claims = parsed.getPayload();
            validateClaims(parsed, claims, expectedScope(request));
            String actorId = required(claims, "actor_id");
            String requestId = required(claims, "request_id");
            String scope = required(claims, "scope");
            String commandId = claims.get("command_id", String.class);
            if (!"GET".equalsIgnoreCase(request.getMethod())
                    && (commandId == null || commandId.isBlank())) {
                throw new IllegalArgumentException("missing command claim");
            }
            var authentication = new UsernamePasswordAuthenticationToken(
                    claims.getSubject(), authorization.substring(7),
                    List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN_INTERNAL")));
            authentication.setDetails(
                    new SystemAdminAuthenticationDetails(actorId, scope, requestId, commandId));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException error) {
            SecurityContextHolder.clearContext();
            reject(response, 401, "SYSTEM_ADMIN_TOKEN_INVALID", "System administration token is invalid");
        }
    }

    private void validateClaims(Jws<Claims> parsed, Claims claims, String expectedScope) {
        if (!"HS256".equals(parsed.getHeader().getAlgorithm())) {
            throw new IllegalArgumentException("invalid algorithm");
        }
        if (!properties.getIssuer().equals(claims.getIssuer())
                || claims.getAudience() == null
                || !claims.getAudience().contains(properties.getAudience())) {
            throw new IllegalArgumentException("invalid issuer or audience");
        }
        if (claims.getIssuedAt() == null || claims.getExpiration() == null
                || Duration.between(claims.getIssuedAt().toInstant(), claims.getExpiration().toInstant())
                .compareTo(Duration.ofSeconds(properties.getMaximumTokenSeconds())) > 0
                || claims.getExpiration().toInstant().isBefore(Instant.now())) {
            throw new IllegalArgumentException("invalid lifetime");
        }
        if (!expectedScope.equals(required(claims, "scope"))
                || claims.containsKey("tenant_id") || claims.containsKey("organization_id")
                || claims.containsKey("tenant_role")) {
            throw new IllegalArgumentException("invalid scope or tenant claims");
        }
        required(claims, "actor_id");
        required(claims, "request_id");
        if (claims.getId() == null || claims.getId().isBlank()
                || claims.getSubject() == null || claims.getSubject().isBlank()) {
            throw new IllegalArgumentException("missing identity claims");
        }
    }

    private static String expectedScope(HttpServletRequest request) {
        String path = request.getRequestURI().substring(PATH_PREFIX.length());
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method) && path.equals("health")) return "system-admin:health:read";
        if("GET".equalsIgnoreCase(method) && java.util.Set.of("business-audit","resource-capabilities","agent-change-evidence").contains(path))return "system-admin:business-evidence:read";
        if("GET".equalsIgnoreCase(method) && java.util.Set.of("usage/summary","usage/history").contains(path))return "system-admin:usage:read";
        if ("GET".equalsIgnoreCase(method) && path.equals("overview")) return "system-admin:overview:read";
        if ("GET".equalsIgnoreCase(method) && path.equals("presence")) return "system-admin:presence:read";
        if ("GET".equalsIgnoreCase(method) && path.equals("resource-observations")) return "system-admin:resources:observations:read";
        if ("GET".equalsIgnoreCase(method) && (path.equals("users") || path.matches("users/[^/]+")))
            return "system-admin:users:read";
        if ("GET".equalsIgnoreCase(method) && path.matches("users/[^/]+/providers"))
            return "system-admin:users:providers:read";
        if ("GET".equalsIgnoreCase(method) && path.matches("users/[^/]+/agents"))
            return "system-admin:users:agents:read";
        if ("GET".equalsIgnoreCase(method)
                && (path.matches("users/[^/]+/resources")
                || path.matches("users/[^/]+/resource-overview")))
            return "system-admin:users:resources:read";
        if ("GET".equalsIgnoreCase(method) && path.equals("organizations"))
            return "system-admin:organizations:read";
        if ("GET".equalsIgnoreCase(method)
                && path.matches("organizations/[^/]+/members"))
            return "system-admin:organizations:members:read";
        if ("GET".equalsIgnoreCase(method) && path.equals("credential-inventory"))
            return "system-admin:credentials:read";
        if ("GET".equalsIgnoreCase(method) && path.matches("commands/[0-9a-fA-F-]+"))
            return "system-admin:commands:read";
        if ("GET".equalsIgnoreCase(method)
                && (path.equals("cleanup-jobs") || path.equals("cleanup-jobs/overview")
                || path.matches("cleanup-jobs/[^/]+/[^/]+")))
            return "system-admin:cleanup:read";
        if ("GET".equalsIgnoreCase(method)
                && path.matches("users/[^/]+/deletion-jobs/current"))
            return "system-admin:users:deletion:read";
        if ("GET".equalsIgnoreCase(method) && path.equals("mcp-registry/sync-jobs"))
            return "system-admin:mcp-registry:read";
        if ("GET".equalsIgnoreCase(method)
                && (path.equals("mcp-registry/candidates")
                || path.matches("mcp-registry/candidates/[^/]+")))
            return "system-admin:mcp-registry:read";
        if ("POST".equalsIgnoreCase(method) && path.equals("users"))
            return "system-admin:users:create";
        if ("PATCH".equalsIgnoreCase(method) && path.matches("users/[^/]+"))
            return "system-admin:users:update";
        if ("POST".equalsIgnoreCase(method) && path.matches("users/[^/]+/session-revocations"))
            return "system-admin:users:sessions:revoke";
        if ("POST".equalsIgnoreCase(method) && path.matches("users/[^/]+/password-resets"))
            return "system-admin:users:password:reset";
        if ("POST".equalsIgnoreCase(method) && path.matches("users/[^/]+/suspend"))
            return "system-admin:users:suspend";
        if ("POST".equalsIgnoreCase(method) && path.matches("users/[^/]+/restore"))
            return "system-admin:users:restore";
        if ("POST".equalsIgnoreCase(method) && path.matches("users/[^/]+/deletion-requests"))
            return "system-admin:users:deletion-preflight";
        if ("POST".equalsIgnoreCase(method) && path.matches("users/[^/]+/deletion-jobs"))
            return "system-admin:users:delete";
        if ("POST".equalsIgnoreCase(method) && path.equals("organizations"))
            return "system-admin:organizations:create";
        if ("PATCH".equalsIgnoreCase(method) && path.matches("organizations/[^/]+"))
            return "system-admin:organizations:update";
        if ("POST".equalsIgnoreCase(method)
                && path.matches("organizations/[^/]+/deletion-jobs"))
            return "system-admin:organizations:delete";
        if ("POST".equalsIgnoreCase(method)
                && path.matches("organizations/[^/]+/members"))
            return "system-admin:organizations:members:add";
        if ("PATCH".equalsIgnoreCase(method)
                && path.matches("organizations/[^/]+/members/[^/]+"))
            return "system-admin:organizations:members:role-update";
        if ("DELETE".equalsIgnoreCase(method)
                && path.matches("organizations/[^/]+/members/[^/]+"))
            return "system-admin:organizations:members:remove";
        if ("POST".equalsIgnoreCase(method)
                && path.matches("organizations/[^/]+/ownership-transfers"))
            return "system-admin:organizations:owner-transfer";
        if ("POST".equalsIgnoreCase(method) && path.equals("mcp-registry/sync-jobs"))
            return "system-admin:mcp-registry:sync";
        if ("POST".equalsIgnoreCase(method)
                && path.matches("mcp-registry/candidates/[^/]+/approvals"))
            return "system-admin:mcp-registry:approve";
        if ("POST".equalsIgnoreCase(method)
                && path.matches("mcp-registry/candidates/[^/]+/rejections"))
            return "system-admin:mcp-registry:reject";
        if ("POST".equalsIgnoreCase(method)
                && path.matches("artifact-objects/[^/]+/deletion-retries"))
            return "system-admin:artifacts:deletion:retry";
        throw new IllegalArgumentException("unsupported system admin path");
    }

    private static boolean hasClientCertificate(HttpServletRequest request) {
        Object value = request.getAttribute("jakarta.servlet.request.X509Certificate");
        return value instanceof X509Certificate[] certificates && certificates.length > 0;
    }

    private static String required(Claims claims, String name) {
        String value = claims.get(name, String.class);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("missing claim " + name);
        return value;
    }

    private static void reject(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"success\":false,\"code\":\"" + code
                + "\",\"message\":\"" + message + "\"}");
    }
}
