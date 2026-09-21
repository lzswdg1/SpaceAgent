package com.spaceagent.platform.integration;

import com.spaceagent.platform.integration.infrastructure.SystemAdminInternalAuthenticationFilter;
import com.spaceagent.platform.integration.infrastructure.SystemAdminSecurityProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SystemAdminInternalAuthenticationFilterTest {
    private static final String SECRET = "system-admin-filter-test-secret-0123456789-abcdef";

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void requiresClientCertificateBeforeAcceptingDedicatedToken() throws Exception {
        SystemAdminInternalAuthenticationFilter filter = new SystemAdminInternalAuthenticationFilter(properties());
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("SYSTEM_ADMIN_MTLS_REQUIRED");
    }

    @Test
    void acceptsCertificateAndExactScopeWithoutTenantContext() throws Exception {
        SystemAdminInternalAuthenticationFilter filter = new SystemAdminInternalAuthenticationFilter(properties());
        MockHttpServletRequest request = request();
        request.setAttribute("jakarta.servlet.request.X509Certificate",
                new X509Certificate[]{mock(X509Certificate.class)});
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                .extracting("authority").containsExactly("ROLE_SYSTEM_ADMIN_INTERNAL");
    }

    @Test
    void writeRequiresCommandClaimAndExactWriteScope() throws Exception {
        SystemAdminInternalAuthenticationFilter filter = new SystemAdminInternalAuthenticationFilter(properties());
        UUID commandId = UUID.randomUUID();
        MockHttpServletRequest missingCommand = request("POST", "/internal/system-admin/v1/users",
                "system-admin:users:create", null);
        missingCommand.setAttribute("jakarta.servlet.request.X509Certificate",
                new X509Certificate[]{mock(X509Certificate.class)});
        MockHttpServletResponse missingResponse = new MockHttpServletResponse();

        filter.doFilter(missingCommand, missingResponse, new MockFilterChain());

        assertThat(missingResponse.getStatus()).isEqualTo(401);
        assertThat(missingResponse.getContentAsString()).contains("SYSTEM_ADMIN_TOKEN_INVALID");

        MockHttpServletRequest wrongScope = request("POST", "/internal/system-admin/v1/users",
                "system-admin:users:read", commandId);
        wrongScope.setAttribute("jakarta.servlet.request.X509Certificate",
                new X509Certificate[]{mock(X509Certificate.class)});
        MockHttpServletResponse wrongScopeResponse = new MockHttpServletResponse();

        filter.doFilter(wrongScope, wrongScopeResponse, new MockFilterChain());

        assertThat(wrongScopeResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest accepted = request("POST", "/internal/system-admin/v1/users",
                "system-admin:users:create", commandId);
        accepted.setAttribute("jakarta.servlet.request.X509Certificate",
                new X509Certificate[]{mock(X509Certificate.class)});
        MockHttpServletResponse acceptedResponse = new MockHttpServletResponse();

        filter.doFilter(accepted, acceptedResponse, new MockFilterChain());

        assertThat(acceptedResponse.getStatus()).isEqualTo(200);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getDetails())
                .extracting("commandId").isEqualTo(commandId.toString());
    }

    @Test
    void userDeletionUsesDedicatedWriteAndJobReadScopes() throws Exception {
        SystemAdminInternalAuthenticationFilter filter = new SystemAdminInternalAuthenticationFilter(properties());
        UUID commandId = UUID.randomUUID();
        MockHttpServletRequest wrong = request("POST",
                "/internal/system-admin/v1/users/user-1/deletion-jobs",
                "system-admin:users:deletion-preflight", commandId);
        wrong.setAttribute("jakarta.servlet.request.X509Certificate",
                new X509Certificate[]{mock(X509Certificate.class)});
        MockHttpServletResponse wrongResponse = new MockHttpServletResponse();
        filter.doFilter(wrong, wrongResponse, new MockFilterChain());
        assertThat(wrongResponse.getStatus()).isEqualTo(401);

        MockHttpServletRequest write = request("POST",
                "/internal/system-admin/v1/users/user-1/deletion-jobs",
                "system-admin:users:delete", commandId);
        write.setAttribute("jakarta.servlet.request.X509Certificate",
                new X509Certificate[]{mock(X509Certificate.class)});
        MockHttpServletResponse writeResponse = new MockHttpServletResponse();
        filter.doFilter(write, writeResponse, new MockFilterChain());
        assertThat(writeResponse.getStatus()).isEqualTo(200);

        MockHttpServletRequest read = request("GET",
                "/internal/system-admin/v1/users/user-1/deletion-jobs/current",
                "system-admin:users:deletion:read", null);
        read.setAttribute("jakarta.servlet.request.X509Certificate",
                new X509Certificate[]{mock(X509Certificate.class)});
        MockHttpServletResponse readResponse = new MockHttpServletResponse();
        filter.doFilter(read, readResponse, new MockFilterChain());
        assertThat(readResponse.getStatus()).isEqualTo(200);
    }

    @Test
    void organizationMutationsAndUserResourcesUseExactScopes() throws Exception {
        SystemAdminInternalAuthenticationFilter filter =
                new SystemAdminInternalAuthenticationFilter(properties());
        UUID commandId = UUID.randomUUID();
        assertAccepted(filter, "POST", "/internal/system-admin/v1/organizations",
                "system-admin:organizations:create", commandId);
        assertAccepted(filter, "PATCH", "/internal/system-admin/v1/organizations/org-1",
                "system-admin:organizations:update", commandId);
        assertAccepted(filter, "POST",
                "/internal/system-admin/v1/organizations/org-1/deletion-jobs",
                "system-admin:organizations:delete", commandId);
        assertAccepted(filter, "GET", "/internal/system-admin/v1/users/user-1/providers",
                "system-admin:users:providers:read", null);
        assertAccepted(filter, "GET", "/internal/system-admin/v1/users/user-1/agents",
                "system-admin:users:agents:read", null);
        assertAccepted(filter, "GET", "/internal/system-admin/v1/users/user-1/resource-overview",
                "system-admin:users:resources:read", null);
        assertAccepted(filter, "GET", "/internal/system-admin/v1/users/user-1/resources",
                "system-admin:users:resources:read", null);
        assertAccepted(filter, "GET",
                "/internal/system-admin/v1/organizations/org-1/members",
                "system-admin:organizations:members:read", null);
        assertAccepted(filter, "POST",
                "/internal/system-admin/v1/organizations/org-1/members",
                "system-admin:organizations:members:add", commandId);
        assertAccepted(filter, "PATCH",
                "/internal/system-admin/v1/organizations/org-1/members/user-1",
                "system-admin:organizations:members:role-update", commandId);
        assertAccepted(filter, "DELETE",
                "/internal/system-admin/v1/organizations/org-1/members/user-1",
                "system-admin:organizations:members:remove", commandId);
        assertAccepted(filter, "POST",
                "/internal/system-admin/v1/organizations/org-1/ownership-transfers",
                "system-admin:organizations:owner-transfer", commandId);
        assertAccepted(filter, "GET", "/internal/system-admin/v1/cleanup-jobs",
                "system-admin:cleanup:read", null);
        assertAccepted(filter, "GET", "/internal/system-admin/v1/cleanup-jobs/USER/user-1",
                "system-admin:cleanup:read", null);
        assertAccepted(filter, "POST",
                "/internal/system-admin/v1/artifact-objects/object-1/deletion-retries",
                "system-admin:artifacts:deletion:retry", commandId);

        MockHttpServletRequest wrong = request("GET",
                "/internal/system-admin/v1/users/user-1/providers",
                "system-admin:users:read", null);
        wrong.setAttribute("jakarta.servlet.request.X509Certificate",
                new X509Certificate[]{mock(X509Certificate.class)});
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(wrong, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(401);

        MockHttpServletRequest wrongResource = request("GET",
                "/internal/system-admin/v1/users/user-1/resources",
                "system-admin:users:read", null);
        wrongResource.setAttribute("jakarta.servlet.request.X509Certificate",
                new X509Certificate[]{mock(X509Certificate.class)});
        MockHttpServletResponse wrongResourceResponse = new MockHttpServletResponse();
        filter.doFilter(wrongResource, wrongResourceResponse, new MockFilterChain());
        assertThat(wrongResourceResponse.getStatus()).isEqualTo(401);
    }

    @Test
    void mcpRegistrySynchronizationAndReviewUseDistinctExactScopes() throws Exception {
        SystemAdminInternalAuthenticationFilter filter =
                new SystemAdminInternalAuthenticationFilter(properties());
        UUID commandId = UUID.randomUUID();
        assertAccepted(filter, "GET", "/internal/system-admin/v1/mcp-registry/sync-jobs",
                "system-admin:mcp-registry:read", null);
        assertAccepted(filter, "GET", "/internal/system-admin/v1/mcp-registry/candidates",
                "system-admin:mcp-registry:read", null);
        assertAccepted(filter, "GET", "/internal/system-admin/v1/mcp-registry/candidates/item-1",
                "system-admin:mcp-registry:read", null);
        assertAccepted(filter, "POST", "/internal/system-admin/v1/mcp-registry/sync-jobs",
                "system-admin:mcp-registry:sync", commandId);
        assertAccepted(filter, "POST",
                "/internal/system-admin/v1/mcp-registry/candidates/item-1/approvals",
                "system-admin:mcp-registry:approve", commandId);
        assertAccepted(filter, "POST",
                "/internal/system-admin/v1/mcp-registry/candidates/item-1/rejections",
                "system-admin:mcp-registry:reject", commandId);

        MockHttpServletRequest wrong = request("POST",
                "/internal/system-admin/v1/mcp-registry/candidates/item-1/approvals",
                "system-admin:mcp-registry:read", commandId);
        wrong.setAttribute("jakarta.servlet.request.X509Certificate",
                new X509Certificate[]{mock(X509Certificate.class)});
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(wrong, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(401);
    }

    private void assertAccepted(SystemAdminInternalAuthenticationFilter filter, String method,
                                String path, String scope, UUID commandId) throws Exception {
        SecurityContextHolder.clearContext();
        MockHttpServletRequest request = request(method, path, scope, commandId);
        request.setAttribute("jakarta.servlet.request.X509Certificate",
                new X509Certificate[]{mock(X509Certificate.class)});
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest request() {
        return request("GET", "/internal/system-admin/v1/overview",
                "system-admin:overview:read", null);
    }

    private MockHttpServletRequest request(
            String method, String path, String scope, UUID commandId) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.addHeader("Authorization", "Bearer " + token(scope, commandId));
        return request;
    }

    private String token() {
        return token("system-admin:overview:read", null);
    }

    private String token(String scope, UUID commandId) {
        Instant now = Instant.now();
        var builder = Jwts.builder().id(UUID.randomUUID().toString())
                .issuer("spaceagent-platform-admin")
                .audience().add("spaceagent-platform-admin-internal").and()
                .subject("platform-admin-server")
                .claim("actor_id", UUID.randomUUID().toString())
                .claim("scope", scope)
                .claim("request_id", UUID.randomUUID().toString())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(45)));
        if (commandId != null) builder.claim("command_id", commandId.toString());
        return builder.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)),
                        Jwts.SIG.HS256).compact();
    }

    private SystemAdminSecurityProperties properties() {
        SystemAdminSecurityProperties properties = new SystemAdminSecurityProperties();
        properties.setJwtSecret(SECRET);
        properties.setAllowInsecureLocal(false);
        return properties;
    }
}
