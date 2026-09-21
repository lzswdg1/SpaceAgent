package com.spaceagent.platform.identity;

import com.spaceagent.platform.identity.api.IdentitySessionView;
import com.spaceagent.platform.identity.api.RegisterIdentityCommand;
import com.spaceagent.platform.identity.application.IdentityApplicationService;
import com.spaceagent.platform.identity.application.IdentityAuthenticationService;
import com.spaceagent.platform.identity.application.IdentityActivityApplicationService;
import com.spaceagent.platform.identity.application.OrganizationApplicationService;
import com.spaceagent.platform.identity.application.OrganizationCleanupApplicationService;
import com.spaceagent.platform.identity.infrastructure.persistence.PostgresIdentityCredentialRepository;
import com.spaceagent.platform.identity.infrastructure.persistence.PostgresIdentityActivityRepository;
import com.spaceagent.platform.identity.infrastructure.persistence.PostgresIdentityRepository;
import com.spaceagent.platform.identity.infrastructure.persistence.PostgresOrganizationCleanupRepository;
import com.spaceagent.platform.identity.infrastructure.persistence.PostgresIdentitySessionRepository;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.UuidGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PostgreSQL proof for M8c Phase 1 membership, refresh rotation and access revocation.
 */
@Testcontainers(disabledWithoutDocker = true)
class PlatformIdentityPostgresIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-08-20T08:00:00Z");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("spaceagent_identity_phase1")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(newDataSource())
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load()
                .migrate();
    }

    @Test
    void sessionsRemainDurableAndRefreshTokensAreSingleUse() {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(newDataSource());
        TimeProvider timeProvider = () -> NOW;
        UuidGenerator idGenerator = new UuidGenerator();
        PostgresIdentityRepository identityRepository = new PostgresIdentityRepository(jdbcTemplate);
        IdentityApplicationService identityApi = new IdentityApplicationService(
                identityRepository, idGenerator, timeProvider);
        PostgresIdentitySessionRepository sessionRepository =
                new PostgresIdentitySessionRepository(jdbcTemplate);
        var cleanup = new OrganizationCleanupApplicationService(
                identityRepository, new PostgresOrganizationCleanupRepository(jdbcTemplate),
                timeProvider, 0, 10, 1);
        IdentityAuthenticationService authenticationService = new IdentityAuthenticationService(
                identityApi,
                new OrganizationApplicationService(
                        identityRepository, idGenerator, timeProvider, cleanup),
                new PostgresIdentityCredentialRepository(jdbcTemplate),
                sessionRepository,
                new IdentityActivityApplicationService(
                        new PostgresIdentityActivityRepository(jdbcTemplate), timeProvider,
                        "identity-activity-test-key-0123456789-abcdef"),
                new BCryptPasswordEncoder(),
                idGenerator,
                timeProvider,
                30);

        var identity = authenticationService.register(new RegisterIdentityCommand(
                "postgres@example.com", "password123", "Postgres User"));
        IdentitySessionView first = authenticationService.openSession(identity);
        IdentitySessionView rotated = authenticationService.rotateRefreshToken(first.refreshToken());

        assertEquals(identity.userId(), rotated.userId());
        assertEquals(identity.tenantId(), rotated.tenantId());
        assertEquals("OWNER", rotated.tenantRole());
        assertNotEquals(first.refreshToken(), rotated.refreshToken());
        assertThrows(BusinessException.class,
                () -> authenticationService.rotateRefreshToken(first.refreshToken()));

        IdentitySessionView sibling = authenticationService.openSession(identity);
        authenticationService.revokeSession(
                new com.spaceagent.platform.identity.api.IdentitySessionApplicationApi.SessionRevocationCommand(
                        identity.userId(), rotated.sessionId(), "session-access-token",
                        NOW.plus(1, ChronoUnit.HOURS), null));
        assertFalse(authenticationService.isSessionActive(
                identity.userId(), rotated.sessionId(), rotated.accessVersion()));
        assertTrue(authenticationService.isSessionActive(
                identity.userId(), sibling.sessionId(), sibling.accessVersion()));
        assertThrows(BusinessException.class,
                () -> authenticationService.rotateRefreshToken(rotated.refreshToken()));
        assertEquals(identity.userId(),
                authenticationService.rotateRefreshToken(sibling.refreshToken()).userId());
        assertTrue(authenticationService.isAccessTokenRevoked("session-access-token"));

        String accessToken = "opaque-access-token";
        authenticationService.revokeAccessToken(
                accessToken,
                identity.userId(),
                NOW.plus(1, ChronoUnit.HOURS));
        assertTrue(authenticationService.isAccessTokenRevoked(accessToken));

        Number memberships = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM platform_tenant_memberships WHERE tenant_id = ? AND user_id = ?",
                Number.class,
                identity.tenantId(),
                identity.userId());
        assertEquals(1L, memberships.longValue());
    }

    private static DriverManagerDataSource newDataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }
}
