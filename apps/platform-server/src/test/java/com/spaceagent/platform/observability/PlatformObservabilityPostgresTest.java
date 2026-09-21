package com.spaceagent.platform.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.api.TenantMembershipView;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.observability.api.ObservabilityApplicationApi;
import com.spaceagent.platform.observability.application.ObservabilityApplicationService;
import com.spaceagent.platform.observability.domain.UsageDimension;
import com.spaceagent.platform.observability.infrastructure.persistence.PostgresAgentOperationalMetricsQueryRepository;
import com.spaceagent.platform.observability.infrastructure.persistence.PostgresObservabilityQueryRepository;
import com.spaceagent.platform.runtime.api.StartAgentRunCommand;
import com.spaceagent.platform.runtime.api.StartRunStepCommand;
import com.spaceagent.platform.runtime.application.RuntimeApplicationService;
import com.spaceagent.platform.runtime.infrastructure.persistence.PostgresRuntimeLedgerRepository;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi;
import com.spaceagent.shared.id.UuidGenerator;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class PlatformObservabilityPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("spaceagent_observability")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    private static DataSource dataSource;
    private static Instant now;

    @BeforeAll
    static void migrateAndSeed() {
        dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .load()
                .migrate();
        now = Instant.now();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at)
                VALUES ('tenant-1','Operations','operations','ACTIVE',?,?)
                """, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at)
                VALUES ('owner-1','tenant-1','owner@example.com','Owner',?,?)
                """, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO platform_tenant_memberships(
                    tenant_id,user_id,tenant_role,status,joined_at,updated_at)
                VALUES ('tenant-1','owner-1','OWNER','ACTIVE',?,?)
                """, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO platform_agent_definitions(
                    id,owner_id,tenant_id,name,description,created_at,updated_at,status,revision)
                VALUES ('agent-1','owner-1','tenant-1','Operations Agent',NULL,?,?,'ACTIVE',1)
                """, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO platform_conversations(
                    id,tenant_id,user_id,agent_id,title,status,created_at,updated_at)
                VALUES ('conversation-1','tenant-1','owner-1','agent-1',
                    'Operations Session','ACTIVE',?,?)
                """, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO platform_model_providers(
                    id,tenant_id,owner_id,name,provider_type,base_url,api_key_ciphertext,
                    auth_type,enabled,is_default,created_at,updated_at)
                VALUES ('provider-1','tenant-1','owner-1','Provider','openai',
                    'https://example.com/v1','encrypted','bearer',TRUE,TRUE,?,?)
                """, Timestamp.from(now), Timestamp.from(now));
    }

    @Test
    void rangesLifetimeAgentRankingAndKnownCostsUseDurableEvidence() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(tx -> {
            tx.setRollbackOnly();
            // Roll back this fixture so the existing runtime-metrics test keeps its own evidence.
            jdbc.update("""
                    INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at)
                    VALUES ('tenant-range','Range','range','ACTIVE',?,?)
                    """, Timestamp.from(now), Timestamp.from(now));
            jdbc.update("""
                    INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at)
                    VALUES ('owner-range','tenant-range','range@example.com','Range',?,?)
                    """, Timestamp.from(now), Timestamp.from(now));
            jdbc.update("""
                    INSERT INTO platform_agent_definitions(
                        id,owner_id,tenant_id,name,created_at,updated_at,status,revision)
                    VALUES ('agent-range','owner-range','tenant-range','First Agent',?,?,'ACTIVE',1)
                    """, Timestamp.from(now), Timestamp.from(now));
            jdbc.update("""
                    INSERT INTO platform_conversations(id,tenant_id,user_id,agent_id,title,status,created_at,updated_at)
                    VALUES ('conversation-range','tenant-range','owner-range','agent-range','Range','ACTIVE',?,?)
                    """, Timestamp.from(now), Timestamp.from(now));
            jdbc.update("""
                    INSERT INTO platform_agent_definitions(
                        id,owner_id,tenant_id,name,created_at,updated_at,status,revision)
                    VALUES ('agent-2','owner-range','tenant-range','Second Agent',?,?,'ACTIVE',1)
                    """, Timestamp.from(now), Timestamp.from(now));
            seedCall(jdbc, "agent-range", now.minusSeconds(3600), 100, 20, 1000000L, "SUCCEEDED");
            seedCall(jdbc, "agent-range", now.minusSeconds(2 * 86400), 200, 30, null, "SUCCEEDED");
            seedCall(jdbc, "agent-2", now.minusSeconds(40 * 86400), 1000, 50, 2000000L, "SUCCEEDED");
            seedCall(jdbc, "agent-range", now.minusSeconds(3600), 0, 0, null, "UNKNOWN");
            seedCall(jdbc, "agent-range", now.minusSeconds(3600), 0, 0, null, "FAILED");
            var repo = new PostgresObservabilityQueryRepository(jdbc);
            var day = repo.overview("tenant-range", now.minusSeconds(86400), "hour");
            assertThat(day.totalInputTokens()).isEqualTo(100);
            assertThat(day.totalCostMicros()).isEqualTo(1000000L);
            assertThat(day.incompleteCost()).isTrue();
            assertThat(repo.overview("tenant-range", now.minusSeconds(7 * 86400), "day").totalInputTokens())
                    .isEqualTo(300);
            var all = repo.overview("tenant-range", null, "month");
            assertThat(all.totalInputTokens()).isEqualTo(1300);
            assertThat(all.totalOutputTokens()).isEqualTo(100);
            assertThat(all.totalCostMicros()).isEqualTo(3000000L);
            assertThat(all.incompleteCost()).isTrue();
            var ranking = repo.usage("tenant-range", null,
                    UsageDimension.AGENT, 201);
            assertThat(ranking).extracting(row -> row.key()).containsExactly("agent-2", "agent-range");
            assertThat(ranking.getFirst().costMicros()).isEqualTo(2000000L);
            assertThat(ranking.getFirst().incompleteCost()).isFalse();
            assertThat(ranking.get(1).incompleteCost()).isTrue();
            assertThat(repo.timeseries("tenant-range", null, "month").stream()
                    .map(p -> p.costMicros()).filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum())
                    .isEqualTo(3000000L);
            assertThat(repo.sessions("tenant-range", null, "all", 0, 100).total()).isEqualTo(5);
            assertThat(repo.overview("other-tenant", null, "month").totalInputTokens()).isZero();
            assertThat(repo.overview("other-tenant", null, "month").totalCostMicros()).isNull();
            assertThat(repo.usage("other-tenant", null,
                    UsageDimension.AGENT, 201)).isEmpty();
            // Fully unpriced remains unknown, but an explicit zero price is known and not incomplete.
            jdbc.update("UPDATE platform_model_call_ledger SET cost_micros=NULL,cost_currency=NULL WHERE model_id='range-model'");
            assertThat(repo.overview("tenant-range", null, "month").totalCostMicros()).isNull();
            jdbc.update("UPDATE platform_model_call_ledger SET cost_micros=0,cost_currency='USD' WHERE model_id='range-model' AND status='SUCCEEDED'");
            var priced = repo.usage("tenant-range", null,
                    UsageDimension.AGENT, 201).getFirst();
            assertThat(priced.costMicros()).isZero();
            assertThat(priced.incompleteCost()).isFalse();
        });
    }

    private void seedCall(JdbcTemplate jdbc, String agent, Instant at, long input, long output,
                          Long cost, String status) {
        var mapper = new ObjectMapper();
        var runtime = new RuntimeApplicationService(new PostgresRuntimeLedgerRepository(jdbc, mapper),
                mock(ToolExecutionLedgerApplicationApi.class), mapper, new UuidGenerator(), () -> at);
        var run = runtime.startRun(new StartAgentRunCommand("tenant-range", "owner-range", agent,
                "00000000-0000-4000-8000-000000000001", "conversation-range", null, null, null, null));
        runtime.markRunInProgress(run.id());
        var step = runtime.startStep(new StartRunStepCommand(run.id(), "inference"));
        jdbc.update("""
                INSERT INTO platform_model_call_ledger(id,agent_run_id,run_step_id,logical_call_id,
                    request_hash,status,provider_id,model_id,revision,input_tokens,output_tokens,
                    cost_micros,cost_currency,created_at,updated_at)
                VALUES (?,?,?,'inference:0',?,?,'provider-1','range-model',1,?,?,?,?,?,?)
                """, java.util.UUID.randomUUID().toString(), run.id(), step.id(), "b".repeat(64),
                status, input, output, cost, cost == null ? null : "USD", Timestamp.from(at), Timestamp.from(at));
    }

    @Test
    void viewsExposeTenantUsageAndStopDelegatesToRuntimeAuthority() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ObjectMapper mapper = new ObjectMapper();
        var runtime = new RuntimeApplicationService(
                new PostgresRuntimeLedgerRepository(jdbc, mapper),
                mock(ToolExecutionLedgerApplicationApi.class), mapper,
                new UuidGenerator(), () -> now);
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        String runId = transaction.execute(ignored -> {
            var run = runtime.startRun(new StartAgentRunCommand(
                    "tenant-1", "owner-1", "agent-1",
                    "00000000-0000-4000-8000-000000000001", "conversation-1",
                    null, null, null, null));
            runtime.markRunInProgress(run.id());
            var step = runtime.startStep(new StartRunStepCommand(run.id(), "inference"));
            jdbc.update("""
                    INSERT INTO platform_model_call_ledger(
                        id,agent_run_id,run_step_id,logical_call_id,request_hash,status,
                        provider_id,model_id,revision,first_chunk_at,first_chunk_ms,response_payload,usage_payload,
                        created_at,updated_at)
                    VALUES ('model-call-1',?,?, 'inference:0',?,'SUCCEEDED',
                        'provider-1','model-1',1,?,15,'{}'::jsonb,
                        '{"inputTokens":120,"outputTokens":30,"cacheReadTokens":10}'::jsonb,
                        ?,?)
                    """, run.id(), step.id(), "a".repeat(64), Timestamp.from(now.plusMillis(15)),
                    Timestamp.from(now), Timestamp.from(now.plusMillis(25)));
            return run.id();
        });

        var repository = new PostgresObservabilityQueryRepository(jdbc);
        IdentityApplicationApi identity = mock(IdentityApplicationApi.class);
        when(identity.findTenantMembership("tenant-1", "owner-1"))
                .thenReturn(Optional.of(new TenantMembershipView(
                        "tenant-1", "owner-1", TenantRole.OWNER,
                        TenantMembershipStatus.ACTIVE, now, now)));
        var service = new ObservabilityApplicationService(
                repository, identity, runtime, () -> now.plusSeconds(1));

        var overview = service.overview(new ObservabilityApplicationApi.MonitoringQuery(
                "tenant-1", "owner-1", "7d"));
        assertThat(overview.overview().totalOrganizations()).isEqualTo(1);
        assertThat(overview.overview().totalAgents()).isEqualTo(1);
        assertThat(overview.overview().runtimeActiveSessions()).isEqualTo(1);
        assertThat(overview.overview().totalInputTokens()).isEqualTo(120);
        assertThat(overview.overview().totalOutputTokens()).isEqualTo(30);
        assertThat(overview.overview().totalCostUsd()).isNull();
        assertThat(overview.overview().hasIncompleteCost()).isTrue();

        var usage = service.usage(new ObservabilityApplicationApi.UsageQuery(
                "tenant-1", "owner-1", "7d", "agent"));
        assertThat(usage.rows()).singleElement().satisfies(row -> {
            assertThat(row.name()).isEqualTo("Operations Agent");
            assertThat(row.totalTokens()).isEqualTo(160);
            assertThat(row.avgLatencyMs()).isEqualTo(25D);
        });
        for (String dimension : java.util.List.of("org", "model", "provider")) {
            assertThat(service.usage(new ObservabilityApplicationApi.UsageQuery(
                    "tenant-1", "owner-1", "7d", dimension)).rows())
                    .as("usage group %s", dimension)
                    .hasSize(1);
        }
        assertThat(service.realtime(new ObservabilityApplicationApi.ActorQuery(
                "tenant-1", "owner-1")).runtimeActive()).isEqualTo(1);
        assertThat(overview.timeseries()).singleElement().satisfies(point ->
                assertThat(point.activeSessions()).isEqualTo(1));
        assertThat(service.sessions(new ObservabilityApplicationApi.SessionsQuery(
                "tenant-1", "owner-1", "7d", "active", 1, 25)).sessions())
                .singleElement().satisfies(session ->
                        assertThat(session.id()).isEqualTo(runId));

        var operational = new PostgresAgentOperationalMetricsQueryRepository(jdbc)
                .snapshot(now.minusSeconds(60), now.plusSeconds(1));
        assertThat(operational.activeRuns()).isEqualTo(1);
        assertThat(operational.runs().total()).isEqualTo(1);
        assertThat(operational.runs().running()).isEqualTo(1);
        assertThat(operational.modelCalls().success()).isEqualTo(1);
        assertThat(operational.modelCalls().unknown()).isZero();
        assertThat(operational.tokens().input()).isEqualTo(120);
        assertThat(operational.tokens().output()).isEqualTo(30);
        assertThat(operational.latencyP95().modelCallMillis()).isEqualTo(25D);
        assertThat(operational.latencyP95().modelFirstChunkMillis()).isEqualTo(15D);
        assertThat(operational.cost().settledMicros()).isZero();
        assertThat(operational.cost().incompleteRuns()).isEqualTo(1);

        transaction.executeWithoutResult(ignored -> service.stopSession(
                new ObservabilityApplicationApi.StopSessionCommand(
                        "tenant-1", "owner-1", runId)));
        assertThat(repository.findSession("tenant-1", runId).orElseThrow().status())
                .isEqualTo("closed");
        assertThat(jdbc.queryForObject(
                "SELECT MAX(installed_rank) FROM flyway_schema_history", Integer.class))
                .isEqualTo(110);
        assertThat(jdbc.queryForObject(
                "SELECT to_regclass('platform_observability_runs') IS NOT NULL", Boolean.class))
                .isTrue();
    }
}
