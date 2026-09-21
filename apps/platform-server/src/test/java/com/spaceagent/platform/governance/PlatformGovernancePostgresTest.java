package com.spaceagent.platform.governance;

import com.spaceagent.platform.governance.domain.ApprovalRequest;
import com.spaceagent.platform.governance.domain.ApprovalScope;
import com.spaceagent.platform.governance.domain.ApprovalState;
import com.spaceagent.platform.governance.domain.GovernanceActionType;
import com.spaceagent.platform.governance.domain.GovernancePolicy;
import com.spaceagent.platform.governance.infrastructure.persistence.PostgresGovernanceRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PlatformGovernancePostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("spaceagent_governance")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    private static final Instant NOW = Instant.parse("2026-08-23T12:00:00Z");
    private static DataSource dataSource;

    @BeforeAll
    static void migrateAndSeed() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load()
                .migrate();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at)
                VALUES ('tenant-1','Governance','governance','ACTIVE',?,?)
                """, Timestamp.from(NOW), Timestamp.from(NOW));
        jdbc.update("""
                INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at)
                VALUES ('owner-1','tenant-1','owner@example.com','Owner',?,?),
                       ('admin-1','tenant-1','admin@example.com','Admin',?,?)
                """, Timestamp.from(NOW), Timestamp.from(NOW),
                Timestamp.from(NOW), Timestamp.from(NOW));
    }

    @Test
    void persistsPolicyDeduplicatesPendingAndConsumesApprovalAtomically() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        var repository = new PostgresGovernanceRepository(
                jdbc, new DataSourceTransactionManager(dataSource));
        var saved = repository.savePolicy(new GovernancePolicy(
                "tenant-1", true, true, true, false, true,
                true, 3_600, 1, "owner-1", NOW), 0).orElseThrow();
        assertThat(saved.revision()).isEqualTo(1);
        assertThat(repository.savePolicy(new GovernancePolicy(
                "tenant-1", false, false, false, false, false,
                false, 3_600, 1, "owner-1", NOW), 0)).isEmpty();

        ApprovalScope scope = new ApprovalScope(
                "tenant-1", "owner-1", GovernanceActionType.COMMAND_EXECUTION,
                "WORKSPACE", "workspace-1", "sha256:" + "a".repeat(64));
        ApprovalRequest first = request(scope, NOW, NOW.plusSeconds(3_600));
        ApprovalRequest duplicate = request(scope, NOW.plusSeconds(1), NOW.plusSeconds(3_601));
        assertThat(repository.createOrFindPending(first).id()).isEqualTo(first.id());
        assertThat(repository.createOrFindPending(duplicate).id()).isEqualTo(first.id());
        ApprovalRequest approved = repository.decide(
                "tenant-1", first.id(), 1, ApprovalState.APPROVED,
                "admin-1", "reviewed", NOW.plusSeconds(2)).orElseThrow();
        assertThat(approved.revision()).isEqualTo(2);

        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            List<CompletableFuture<Boolean>> attempts = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(index -> CompletableFuture.supplyAsync(() -> {
                        try {
                            barrier.await(5, TimeUnit.SECONDS);
                            var local = new PostgresGovernanceRepository(
                                    new JdbcTemplate(dataSource),
                                    new DataSourceTransactionManager(dataSource));
                            return local.consume(first.id(), scope, NOW.plusSeconds(3)).isPresent();
                        } catch (Exception error) {
                            throw new IllegalStateException(error);
                        }
                    }, executor))
                    .toList();
            CompletableFuture.allOf(attempts.toArray(CompletableFuture[]::new))
                    .get(10, TimeUnit.SECONDS);
            assertThat(attempts.stream().map(CompletableFuture::join).filter(Boolean::booleanValue))
                    .hasSize(1);
        }
        assertThat(repository.findApproval("tenant-1", first.id()).orElseThrow().state())
                .isEqualTo(ApprovalState.CONSUMED);

        ApprovalScope expiringScope = new ApprovalScope(
                "tenant-1", "owner-1", GovernanceActionType.CODING_FILE_MUTATION,
                "WORKSPACE", "workspace-1", "sha256:" + "b".repeat(64));
        ApprovalRequest expiring = request(expiringScope, NOW, NOW.plusSeconds(10));
        repository.createOrFindPending(expiring);
        assertThat(repository.listApprovals(
                "tenant-1", ApprovalState.EXPIRED, 10, NOW.plusSeconds(11)))
                .extracting(ApprovalRequest::id).contains(expiring.id());

        assertThat(jdbc.queryForObject(
                "SELECT MAX(installed_rank) FROM flyway_schema_history", Integer.class))
                .isEqualTo(110);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_approval_requests WHERE state='CONSUMED'",
                Integer.class)).isEqualTo(1);
    }

    private static ApprovalRequest request(
            ApprovalScope scope, Instant createdAt, Instant expiresAt) {
        return new ApprovalRequest(
                UUID.randomUUID().toString(), scope.tenantId(), scope.requestedBy(),
                scope.actionType(), scope.resourceType(), scope.resourceId(),
                scope.operationHash(), "Sensitive operation", ApprovalState.PENDING,
                expiresAt, null, null, null, null, 1, createdAt, createdAt);
    }
}
