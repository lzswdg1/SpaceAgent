package com.spaceagent.shared.auth;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class InternalServiceAuthenticationFilterTest {

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void doFilterInternal_authenticates_whenTokenMatches() throws ServletException, IOException {
        InternalServiceAuthenticationFilter filter = new InternalServiceAuthenticationFilter("secret-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/agents/default/runtime-config");
        request.addHeader(InternalServiceAuthenticationFilter.INTERNAL_TOKEN_HEADER, "secret-token");
        request.addHeader(InternalServiceAuthenticationFilter.USER_ID_HEADER, "user-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("user-1", SecurityContextHolder.getContext().getAuthentication().getName());
    }

    @Test
    void doFilterInternal_continuesWithoutAuthentication_whenTokenIsMissing() throws ServletException, IOException {
        InternalServiceAuthenticationFilter filter = new InternalServiceAuthenticationFilter("secret-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/knowledge/retrieve");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(response.getContentAsString().isBlank());
    }

    @Test
    void doFilterInternal_rejects_whenTokenIsInvalid() throws ServletException, IOException {
        InternalServiceAuthenticationFilter filter = new InternalServiceAuthenticationFilter("secret-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/internal/knowledge/retrieve");
        request.addHeader(InternalServiceAuthenticationFilter.INTERNAL_TOKEN_HEADER, "wrong-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertTrue(response.getContentAsString().contains("Invalid internal service token"));
    }

    @Test
    void publicBusinessPath_ignoresInternalIdentityHeaders() throws Exception {
        InternalServiceAuthenticationFilter filter = new InternalServiceAuthenticationFilter("secret-token");
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/agents");
        request.addHeader(InternalServiceAuthenticationFilter.INTERNAL_TOKEN_HEADER, "secret-token");
        request.addHeader(InternalServiceAuthenticationFilter.USER_ID_HEADER, "victim");
        request.addHeader(InternalServiceAuthenticationFilter.TENANT_ID_HEADER, "foreign-tenant");
        request.addHeader(InternalServiceAuthenticationFilter.TENANT_ROLE_HEADER, "OWNER");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }
}
