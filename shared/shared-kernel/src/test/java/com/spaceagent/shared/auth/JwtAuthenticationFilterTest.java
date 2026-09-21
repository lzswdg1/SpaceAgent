package com.spaceagent.shared.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JwtAuthenticationFilterTest {

    private static final String SECRET = "spaceagent-test-jwt-secret-at-least-32-bytes";
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(SECRET);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void preservesExistingInternalTenantAuthentication() throws ServletException, IOException {
        var existing = new UsernamePasswordAuthenticationToken(
                "user-1",
                "internal-token",
                List.of(new SimpleGrantedAuthority("ROLE_INTERNAL"))
        );
        existing.setDetails(new TenantAuthenticationDetails("tenant-organization", "ADMIN"));
        SecurityContextHolder.getContext().setAuthentication(existing);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/chat/conversations");
        request.addHeader("Authorization", "Bearer propagated-user-token");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertEquals("internal-token", authentication.getCredentials());
        assertEquals(
                new TenantAuthenticationDetails("tenant-organization", "ADMIN"),
                authentication.getDetails()
        );
    }

    @Test
    void restoresTenantContextWhenAuthenticatingJwtDirectly() throws ServletException, IOException {
        String token = Jwts.builder()
                .subject("user-1")
                .claim("role", "USER")
                .claim("tenant_id", "tenant-organization")
                .claim("tenant_role", "MEMBER")
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/chat/conversations");
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertEquals("user-1", authentication.getName());
        assertEquals(
                new TenantAuthenticationDetails("tenant-organization", "MEMBER"),
                authentication.getDetails()
        );
    }
}
