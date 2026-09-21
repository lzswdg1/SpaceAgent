package com.spaceagent.shared.auth;

import io.jsonwebtoken.Claims;
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
import java.util.List;

/**
 * JWT 认证过滤器基类。各微服务可直接使用或继承扩展。
 * 从 Authorization: Bearer {token} 头中解析 JWT，设置 SecurityContext。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final SecretKey key;

    public JwtAuthenticationFilter(String jwtSecret) {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("JWT secret is not configured.");
        }
        if (jwtSecret.length() < 32) {
            throw new IllegalStateException("JWT secret must be at least 32 characters.");
        }
        this.key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            // Gateway-to-service calls are authenticated by
            // InternalServiceAuthenticationFilter and carry the authoritative
            // tenant context in internal headers. Do not replace that context
            // merely because the original Bearer token was also propagated.
            filterChain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Claims claims = validateToken(token);
                String userId = claims.getSubject();
                String role = claims.get("role", String.class);
                String tenantId = claimOrDefault(claims, "tenant_id", userId);
                String tenantRole = claimOrDefault(claims, "tenant_role", "OWNER");

                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
                var authentication = new UsernamePasswordAuthenticationToken(userId, token, authorities);
                authentication.setDetails(new TenantAuthenticationDetails(tenantId, tenantRole));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException ignored) {
                // Token 无效，不设置认证信息，继续过滤链
            }
        }
        filterChain.doFilter(request, response);
    }

    private String claimOrDefault(Claims claims, String name, String fallback) {
        String value = claims.get(name, String.class);
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 解析并验证 JWT Token，返回 Claims。
     */
    public Claims validateToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 检查 Token 是否有效。
     */
    public boolean isValid(String token) {
        try {
            validateToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 从当前 SecurityContext 中获取已认证用户的 Token 信息。
     */
    public static AuthToken currentAuthToken() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth instanceof UsernamePasswordAuthenticationToken)) {
            return null;
        }
        String userId = (String) auth.getPrincipal();
        String token = auth.getCredentials() instanceof String ? (String) auth.getCredentials() : null;
        String role = auth.getAuthorities().stream()
                .findFirst()
                .map(a -> a.getAuthority().replace("ROLE_", ""))
                .orElse(null);
        return new AuthToken(token, userId, null, role);
    }

    /**
     * 从当前 SecurityContext 中获取原始 JWT Token 字符串（用于服务间传递）。
     */
    public static String currentRawToken() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth instanceof UsernamePasswordAuthenticationToken)) {
            return null;
        }
        Object credentials = auth.getCredentials();
        return credentials instanceof String ? (String) credentials : null;
    }
}
