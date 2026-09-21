package com.spaceagent.platform.tooling;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.*;
import com.spaceagent.platform.identity.application.IdentityApplicationService;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.infrastructure.persistence.PostgresIdentityRepository;
import com.spaceagent.platform.tooling.api.McpMarketplaceApplicationApi;
import com.spaceagent.platform.tooling.application.McpMarketplaceApplicationService;
import com.spaceagent.platform.tooling.domain.*;
import com.spaceagent.platform.tooling.infrastructure.persistence.*;
import com.spaceagent.shared.id.UuidGenerator;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PlatformMcpInvocationPostgresTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("spaceagent_mcp_invocation")
            .withUsername("spaceagent").withPassword("spaceagent");

    @Test
    void atomicallyClaimsCompletesAndReplaysWithoutRawIdempotencyKey() {
        DriverManagerDataSource dataSource = dataSource();
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var ids = new UuidGenerator();
        var identityRepository = new PostgresIdentityRepository(jdbc);
        var identity = new IdentityApplicationService(identityRepository, ids, Instant::now);
        String tenant = identity.createTenant(new CreateTenantCommand("MCP", "mcp-ledger")).id();
        String user = identity.createUser(new CreateUserCommand(
                tenant, "mcp-ledger@example.com", "MCP")).id();
        identity.addTenantMembership(new AddTenantMembershipCommand(tenant, user, TenantRole.OWNER));
        var marketplaceRepository = new PostgresMcpMarketplaceRepository(jdbc);
        var marketplace = new McpMarketplaceApplicationService(
                marketplaceRepository, identity, new Cipher(), new ObjectMapper(), ids, Instant::now);
        var entry = marketplaceRepository.findEntryBySlug("github").orElseThrow();
        var installation = marketplace.install(new McpMarketplaceApplicationApi.InstallCommand(
                tenant, user, entry.id(), McpInstallationScope.USER, null));
        String connection = marketplace.connect(new McpMarketplaceApplicationApi.ConnectCommand(
                tenant, user, installation.id(), "https://mcp.github.example/mcp",
                McpAuthType.BEARER, Map.of("token", "secret"))).id();

        var transactionManager = new DataSourceTransactionManager(dataSource);
        var repository = new PostgresMcpInvocationLedgerRepository(jdbc, transactionManager);
        String isolatedInvocation = UUID.randomUUID().toString();
        var outer = new TransactionTemplate(transactionManager);
        outer.executeWithoutResult(status -> {
            repository.claim(new McpInvocationLedgerRepository.ClaimRequest(
                    isolatedInvocation, tenant, user, connection,
                    "github-workspace-checkout:requires-new", "f".repeat(64),
                    "github_prepare_checkout", "{}", "0".repeat(64),
                    UUID.randomUUID().toString(), "requires-new-worker", 90));
            assertThat(new JdbcTemplate(dataSource()).queryForObject("""
                    SELECT count(*) FROM platform_mcp_invocation_ledger
                    WHERE id=CAST(? AS UUID)
                    """, Long.class, isolatedInvocation)).isEqualTo(1L);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM platform_mcp_invocation_ledger
                WHERE id=CAST(? AS UUID)
                """, Long.class, isolatedInvocation)).isEqualTo(1L);
        String invocation = UUID.randomUUID().toString();
        String claimToken = UUID.randomUUID().toString();
        var request = new McpInvocationLedgerRepository.ClaimRequest(
                invocation, tenant, user, connection, "github-project-import:project",
                "a".repeat(64), "github_get_repository", "{\"repositoryId\":\"42\"}",
                "b".repeat(64), claimToken, "test-worker", 60);
        var claimed = repository.claim(request);
        assertThat(claimed.type()).isEqualTo(McpInvocationClaimType.CLAIMED);
        assertThat(repository.claim(request).type()).isEqualTo(McpInvocationClaimType.BUSY);
        var completed = repository.complete(new McpInvocationLedgerRepository.CompleteRequest(
                invocation, claimToken, claimed.ledger().revision(),
                McpInvocationStatus.SUCCEEDED, "{\"id\":\"42\"}", null));
        assertThat(completed.type()).isEqualTo(McpInvocationTransitionType.APPLIED);
        assertThat(repository.claim(request).type()).isEqualTo(McpInvocationClaimType.REPLAY);
        assertThat(jdbc.queryForObject("""
                SELECT idempotency_key_hash FROM platform_mcp_invocation_ledger
                WHERE id=CAST(? AS UUID)
                """, String.class, invocation)).isEqualTo("a".repeat(64));

        String checkoutInvocation = UUID.randomUUID().toString();
        String checkoutClaimToken = UUID.randomUUID().toString();
        var checkoutClaim = repository.claim(new McpInvocationLedgerRepository.ClaimRequest(
                checkoutInvocation, tenant, user, connection,
                "github-workspace-checkout:workspace", "c".repeat(64),
                "github_prepare_checkout", "{\"workspaceId\":\"workspace\"}",
                "d".repeat(64), checkoutClaimToken, "checkout-worker", 90));
        Instant expiresAt = Instant.now().plusSeconds(300);
        assertThat(repository.completeWithGrant(
                new McpCheckoutGrantRepository.CompleteGrantRequest(
                        checkoutInvocation, tenant, user, checkoutClaimToken,
                        checkoutClaim.ledger().revision(),
                        "{\"encryptedAuthorizationHeader\":\"ciphertext-only\"}",
                        "e".repeat(64), expiresAt)))
                .isEqualTo(McpInvocationTransitionType.APPLIED);
        assertThat(repository.findAvailable(new McpCheckoutGrantRepository.GrantQuery(
                checkoutInvocation, tenant, user))).isPresent();
        String stored = jdbc.queryForObject("""
                SELECT result_json::text FROM platform_mcp_invocation_ledger
                WHERE id=CAST(? AS UUID)
                """, String.class, checkoutInvocation);
        assertThat(stored).contains("ciphertext-only").doesNotContain("private-secret");
        repository.consume(new McpCheckoutGrantRepository.GrantQuery(
                checkoutInvocation, tenant, user));
        assertThat(repository.findAvailable(new McpCheckoutGrantRepository.GrantQuery(
                checkoutInvocation, tenant, user))).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT result_json IS NULL AND checkout_consumed_at IS NOT NULL
                FROM platform_mcp_invocation_ledger WHERE id=CAST(? AS UUID)
                """, Boolean.class, checkoutInvocation)).isTrue();

        String expiredInvocation = UUID.randomUUID().toString();
        String expiredClaimToken = UUID.randomUUID().toString();
        var expiredClaim = repository.claim(new McpInvocationLedgerRepository.ClaimRequest(
                expiredInvocation, tenant, user, connection,
                "github-workspace-checkout:expired", "1".repeat(64),
                "github_prepare_checkout", "{}", "2".repeat(64),
                expiredClaimToken, "expiry-worker", 90));
        repository.completeWithGrant(new McpCheckoutGrantRepository.CompleteGrantRequest(
                expiredInvocation, tenant, user, expiredClaimToken,
                expiredClaim.ledger().revision(),
                "{\"encryptedAuthorizationHeader\":\"expired-ciphertext\"}",
                "3".repeat(64), Instant.now().minusSeconds(1)));
        assertThat(repository.redactExpired()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT result_json IS NULL AND checkout_consumed_at IS NOT NULL
                FROM platform_mcp_invocation_ledger WHERE id=CAST(? AS UUID)
                """, Boolean.class, expiredInvocation)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success", Long.class))
                .isEqualTo(110L);
    }

    private static DriverManagerDataSource dataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static class Cipher implements McpConnectionSecretCipher {
        public String encrypt(String value) { return "cipher:" + value; }
        public String decrypt(String value) { return value.substring(7); }
    }
}
