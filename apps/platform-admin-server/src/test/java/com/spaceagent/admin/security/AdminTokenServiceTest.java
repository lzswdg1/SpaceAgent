package com.spaceagent.admin.security;

import com.spaceagent.admin.config.AdminSecurityProperties;
import com.spaceagent.admin.identity.domain.AdminPrincipalStatus;
import com.spaceagent.admin.identity.domain.AdminRole;
import com.spaceagent.admin.identity.domain.SystemAdministrator;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminTokenServiceTest {
    @Test
    void issuesIndependentNonTenantAdministratorClaims() {
        AdminSecurityProperties properties = new AdminSecurityProperties();
        properties.setJwtSecret("administrator-test-jwt-secret-32-characters-minimum");
        Instant now = Instant.parse("2026-08-26T00:00:00Z");
        UUID administratorId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        SystemAdministrator administrator = new SystemAdministrator(administratorId, "root", "Root",
                AdminPrincipalStatus.ACTIVE, AdminRole.PLATFORM_SUPER_ADMIN, 1, true,
                false, null, now, now);

        AdminAccessToken token = new AdminTokenService(properties,
                Clock.fixed(now, ZoneOffset.UTC)).issue(administrator, sessionId);
        var claims = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(properties.getJwtSecret().getBytes(StandardCharsets.UTF_8)))
                .clock(() -> java.util.Date.from(now))
                .build().parseSignedClaims(token.value()).getPayload();

        assertThat(claims.getSubject()).isEqualTo(administratorId.toString());
        assertThat(claims.getAudience()).containsExactly(properties.getAudience());
        assertThat(claims.get("roles")).isEqualTo(java.util.List.of("PLATFORM_SUPER_ADMIN"));
        assertThat(claims.get("amr")).isEqualTo(java.util.List.of("pwd"));
        assertThat(claims.get("session_id", String.class)).isEqualTo(sessionId.toString());
        assertThat(claims).doesNotContainKeys("tenant_id", "organization_id", "tenant_role");
    }
}
