package com.spaceagent.platform.automation;

import com.spaceagent.platform.automation.domain.AutomationExecution;
import com.spaceagent.platform.automation.domain.AutomationExecutionState;
import com.spaceagent.platform.automation.domain.AutomationSchedule;
import com.spaceagent.platform.automation.domain.AutomationScheduleState;
import com.spaceagent.platform.automation.domain.AutomationScheduleType;
import com.spaceagent.platform.automation.domain.AutomationTriggerType;
import com.spaceagent.platform.automation.infrastructure.persistence.PostgresAutomationRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PlatformAutomationPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("spaceagent_automation")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    private static final String VERSION_ID = "00000000-0000-4000-8000-000000000001";
    private static DataSource dataSource;

    @BeforeAll
    static void migrateAndSeed() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load().migrate();
        Instant now = Instant.now();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at)
                VALUES ('tenant-1','Automation','automation','ACTIVE',?,?)
                """, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at)
                VALUES ('owner-1','tenant-1','owner@example.com','Owner',?,?)
                """, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO platform_agent_definitions(
                    id,owner_id,tenant_id,name,description,created_at,updated_at,status,revision)
                VALUES ('agent-1','owner-1','tenant-1','Automation Agent',NULL,?,?,'ACTIVE',1)
                """, Timestamp.from(now), Timestamp.from(now));
    }

    @Test
    void postgresClockSkipLockedAndOccurrenceUniquenessAreAuthoritative() throws Exception {
        var repository = repository();
        Instant now = repository.currentTime();
        String scheduleId = UUID.randomUUID().toString();
        repository.createSchedule(schedule(
                scheduleId, now.minusSeconds(1), 1, now));

        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            List<CompletableFuture<Optional<String>>> claims = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(index -> CompletableFuture.supplyAsync(() -> {
                        try {
                            barrier.await(5, TimeUnit.SECONDS);
                            return new TransactionTemplate(
                                    new DataSourceTransactionManager(dataSource)).execute(ignored -> {
                                var local = repository();
                                var due = local.lockNextDueSchedule();
                                due.ifPresent(value -> local.updateSchedule(
                                        schedule(scheduleId, value.databaseNow().plusSeconds(3600),
                                                value.schedule().revision() + 1,
                                                value.databaseNow()),
                                        value.schedule().revision()));
                                return due.map(value -> value.schedule().id());
                            });
                        } catch (Exception error) {
                            throw new IllegalStateException(error);
                        }
                    }, executor)).toList();
            CompletableFuture.allOf(claims.toArray(CompletableFuture[]::new))
                    .get(10, TimeUnit.SECONDS);
            assertThat(claims.stream().map(CompletableFuture::join).filter(Optional::isPresent))
                    .hasSize(1);
        }

        String executionA = UUID.randomUUID().toString();
        String executionB = UUID.randomUUID().toString();
        AutomationExecution first = execution(executionA, scheduleId, now);
        AutomationExecution duplicate = execution(executionB, scheduleId, now);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var left = CompletableFuture.supplyAsync(
                    () -> repository().createOrFindExecution(first).id(), executor);
            var right = CompletableFuture.supplyAsync(
                    () -> repository().createOrFindExecution(duplicate).id(), executor);
            CompletableFuture.allOf(left, right).get(10, TimeUnit.SECONDS);
            assertThat(left.join()).isEqualTo(right.join());
        }

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject(
                "SELECT MAX(installed_rank) FROM flyway_schema_history", Integer.class))
                .isEqualTo(110);
        assertThat(jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conname='ck_platform_runtime_continuation_type'",
                String.class)).contains("AUTOMATION_EXECUTION");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_automation_executions", Integer.class))
                .isEqualTo(1);
    }

    private static PostgresAutomationRepository repository() {
        return new PostgresAutomationRepository(new JdbcTemplate(dataSource));
    }

    private static AutomationSchedule schedule(
            String id, Instant next, long revision, Instant now) {
        return new AutomationSchedule(
                id, "tenant-1", "owner-1", "agent-1", "Periodic", "summarize",
                AutomationScheduleType.PERIODIC, "0 0 * * * *", null, "UTC",
                AutomationScheduleState.ACTIVE, next, null, null, null,
                0, 1, revision, now, now, null);
    }

    private static AutomationExecution execution(String id, String scheduleId, Instant now) {
        return new AutomationExecution(
                id, scheduleId, "tenant-1", "owner-1", "agent-1", VERSION_ID,
                "scheduled:" + now, now, AutomationTriggerType.SCHEDULED,
                AutomationExecutionState.REJECTED,
                "sha256:" + "b".repeat(64), null, null, null, null, null,
                null, now, "not authorized", 0, 0, 1, now, now);
    }
}
