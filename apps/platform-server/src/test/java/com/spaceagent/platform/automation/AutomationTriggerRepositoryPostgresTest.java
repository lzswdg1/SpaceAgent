package com.spaceagent.platform.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.automation.domain.AutomationTrigger;
import com.spaceagent.platform.automation.domain.AutomationTriggerPersistence;
import com.spaceagent.platform.automation.domain.AutomationTriggerSource;
import com.spaceagent.platform.automation.domain.AutomationTriggerState;
import com.spaceagent.platform.automation.domain.AutomationTriggerType;
import com.spaceagent.platform.automation.domain.AutomationDispatchPlan;
import com.spaceagent.platform.automation.infrastructure.persistence.PostgresAutomationDispatchPlanRepository;
import com.spaceagent.platform.automation.infrastructure.persistence.PostgresAutomationTriggerRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class AutomationTriggerRepositoryPostgresTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private static final String HASH_A = "sha256:" + "a".repeat(64);
    private static final String HASH_B = "sha256:" + "b".repeat(64);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("automation_trigger").withUsername("spaceagent").withPassword("spaceagent");

    private static DriverManagerDataSource dataSource;

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load().migrate();
    }

    @Test
    void persistsVersionSubscriptionDedupDeliveryDeadLetterAndRestart() {
        Scope scope = seed(new JdbcTemplate(dataSource), "one");
        var repository = repository(dataSource);
        AutomationTrigger trigger = trigger(scope);
        repository.insertTrigger(trigger);
        assertThat(repository.findTrigger(scope.tenantId(), scope.ownerId(), trigger.id()))
                .contains(trigger);
        assertThat(repository.findTriggerLineage(scope.tenantId(), scope.ownerId(), trigger.lineageId()))
                .containsExactly(trigger);

        AutomationTrigger active = trigger.activate(NOW.plusSeconds(1));
        assertThat(repository.updateTrigger(active, 1)).contains(active);
        assertThat(repository.updateTrigger(active, 1)).isEmpty();

        var subscription = new AutomationTriggerPersistence.Subscription(
                UUID.randomUUID().toString(), trigger.id(), trigger.lineageId(), scope.tenantId(),
                scope.ownerId(), AutomationTriggerType.WEBHOOK, HASH_A,
                AutomationTriggerPersistence.SubscriptionState.ACTIVE, 1, NOW, NOW, null);
        repository.insertSubscription(subscription);

        var occurrence = occurrence(trigger, subscription, scope, UUID.randomUUID().toString());
        var duplicate = occurrence(trigger, subscription, scope, UUID.randomUUID().toString());
        assertThat(repository.createOrFindOccurrence(occurrence)).isEqualTo(occurrence);
        assertThat(repository.createOrFindOccurrence(duplicate).id()).isEqualTo(occurrence.id());

        var delivery = new AutomationTriggerPersistence.Delivery(
                UUID.randomUUID().toString(), occurrence.id(), scope.tenantId(), scope.ownerId(), 1,
                AutomationTriggerPersistence.DeliveryState.PENDING, NOW, null, null, null,
                0, null, null, 1, NOW, NOW);
        repository.insertDelivery(delivery);
        var dispatchPlans = new PostgresAutomationDispatchPlanRepository(new JdbcTemplate(dataSource));
        var dispatchPlan = new AutomationDispatchPlan(
                UUID.randomUUID().toString(), occurrence.id(), delivery.id(), scope.tenantId(),
                scope.ownerId(), HASH_A, null, UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                AutomationDispatchPlan.Phase.RESERVED, null, 1, NOW, NOW);
        assertThat(dispatchPlans.createOrFind(dispatchPlan)).isEqualTo(dispatchPlan);
        var conversationReady = dispatchPlan.advance(
                AutomationDispatchPlan.Phase.CONVERSATION_READY, NOW.plusSeconds(1));
        assertThat(dispatchPlans.update(conversationReady, dispatchPlan.revision()))
                .contains(conversationReady);
        var failed = new AutomationTriggerPersistence.Delivery(
                delivery.id(), occurrence.id(), scope.tenantId(), scope.ownerId(), 1,
                AutomationTriggerPersistence.DeliveryState.FAILED, NOW.plusSeconds(60), null, null, null,
                1, "DELIVERY_REJECTED", HASH_B, 2, NOW, NOW.plusSeconds(2));
        assertThat(repository.updateDelivery(failed, 1)).contains(failed);
        assertThat(repository.updateDelivery(failed, 1)).isEmpty();

        var deadLetter = new AutomationTriggerPersistence.DeadLetter(
                UUID.randomUUID().toString(), occurrence.id(), delivery.id(), scope.tenantId(),
                scope.ownerId(), "RETRY_EXHAUSTED", HASH_B, NOW.plusSeconds(3));
        assertThat(repository.createOrFindDeadLetter(deadLetter)).isEqualTo(deadLetter);
        assertThat(repository.createOrFindDeadLetter(new AutomationTriggerPersistence.DeadLetter(
                UUID.randomUUID().toString(), occurrence.id(), delivery.id(), scope.tenantId(),
                scope.ownerId(), "RETRY_EXHAUSTED", HASH_B, NOW.plusSeconds(4))).id())
                .isEqualTo(deadLetter.id());

        var restarted = repository(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        assertThat(restarted.findSubscription(scope.tenantId(), scope.ownerId(), subscription.id()))
                .contains(subscription);
        assertThat(restarted.findOccurrence(scope.tenantId(), scope.ownerId(), occurrence.id()))
                .contains(occurrence);
        assertThat(restarted.findDelivery(scope.tenantId(), scope.ownerId(), delivery.id()))
                .contains(failed);
        assertThat(new PostgresAutomationDispatchPlanRepository(new JdbcTemplate(dataSource))
                .findByOccurrence(scope.tenantId(), scope.ownerId(), occurrence.id()))
                .contains(conversationReady);
    }

    @Test
    void databaseRejectsCrossTenantAndRawPayloadIsAbsent() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        Scope one = seed(jdbc, "scope-one");
        Scope two = seed(jdbc, "scope-two");
        AutomationTrigger trigger = trigger(one);
        repository(dataSource).insertTrigger(trigger);

        var crossTenant = new AutomationTriggerPersistence.Subscription(
                UUID.randomUUID().toString(), trigger.id(), trigger.lineageId(), two.tenantId(),
                two.ownerId(), AutomationTriggerType.WEBHOOK, HASH_A,
                AutomationTriggerPersistence.SubscriptionState.ACTIVE, 1, NOW, NOW, null);
        assertThatThrownBy(() -> repository(dataSource).insertSubscription(crossTenant))
                .isInstanceOf(Exception.class);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_name LIKE 'platform_automation_trigger_%'
                   AND column_name IN ('payload','body','signature','credential','secret')
                """, Integer.class)).isZero();
    }

    @Test
    void v1063UpgradesIdempotently() {
        String schema = "automation_trigger_upgrade";
        Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime").target(MigrationVersion.fromVersion("1062"))
                .load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(
                com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema));
        assertThat(jdbc.queryForObject(
                "SELECT to_regclass('platform_automation_triggers') IS NULL", Boolean.class)).isTrue();

        Flyway flyway = Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime").load();
        flyway.migrate();
        flyway.migrate();
        assertThat(jdbc.queryForObject(
                "SELECT to_regclass('platform_automation_trigger_dead_letters') IS NOT NULL",
                Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history WHERE success
                 ORDER BY installed_rank DESC LIMIT 1
                """, String.class)).isEqualTo("1098");
    }

    @Test
    void v1077AddsRestartableDispatchPlanWithoutChangingPriorHistory() {
        String schema = "automation_dispatch_upgrade";
        Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1076")).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(
                com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema));
        assertThat(jdbc.queryForObject(
                "SELECT to_regclass('platform_automation_dispatch_plans') IS NULL", Boolean.class))
                .isTrue();
        Flyway flyway = Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(MigrationVersion.fromVersion("1077")).load();
        flyway.migrate();
        flyway.migrate();
        assertThat(jdbc.queryForObject(
                "SELECT to_regclass('platform_automation_dispatch_plans') IS NOT NULL", Boolean.class))
                .isTrue();
    }

    private static PostgresAutomationTriggerRepository repository(javax.sql.DataSource source) {
        return new PostgresAutomationTriggerRepository(new JdbcTemplate(source), new ObjectMapper());
    }

    private static AutomationTrigger trigger(Scope scope) {
        String id = UUID.randomUUID().toString();
        String lineage = UUID.randomUUID().toString();
        var source = new AutomationTriggerSource.Webhook(
                List.of("webhook-key:primary"), "HMAC_SHA256", 32_000, 300);
        String hash = AutomationTrigger.calculateConfigSha256(
                scope.tenantId(), scope.ownerId(), scope.agentId(), "Webhook", "process",
                AutomationTriggerType.WEBHOOK, source);
        return new AutomationTrigger(id, lineage, 1, null, scope.tenantId(), scope.ownerId(),
                scope.agentId(), "Webhook", "process", AutomationTriggerType.WEBHOOK,
                source, hash, AutomationTriggerState.DRAFT, 1, NOW, null, null);
    }

    private static AutomationTriggerPersistence.Occurrence occurrence(
            AutomationTrigger trigger,
            AutomationTriggerPersistence.Subscription subscription,
            Scope scope,
            String id) {
        return new AutomationTriggerPersistence.Occurrence(id, trigger.id(), trigger.lineageId(),
                subscription.id(), scope.tenantId(), scope.ownerId(), HASH_A, HASH_B,
                AutomationTriggerPersistence.OccurrenceState.RECEIVED, NOW, null, 1, NOW, NOW);
    }

    private static Scope seed(JdbcTemplate jdbc, String suffix) {
        String tenant = UUID.randomUUID().toString();
        String owner = UUID.randomUUID().toString();
        String agent = "automation-" + suffix;
        Timestamp now = Timestamp.from(NOW);
        jdbc.update("""
                INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at)
                VALUES(?,?,?,'ACTIVE',?,?)
                """, tenant, "Tenant " + suffix, "automation-" + tenant, now, now);
        jdbc.update("""
                INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at)
                VALUES(?,?,?,'Owner',?,?)
                """, owner, tenant, owner + "@example.com", now, now);
        jdbc.update("""
                INSERT INTO platform_agent_definitions(
                    id,owner_id,tenant_id,name,status,revision,created_at,updated_at)
                VALUES(?,?,?,?,'ACTIVE',1,?,?)
                """, agent, owner, tenant, "Agent " + suffix, now, now);
        return new Scope(tenant, owner, agent);
    }

    private record Scope(String tenantId, String ownerId, String agentId) {
    }
}
