package com.spaceagent.platform.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.runtime.api.ResumeAgentRunCommand;
import com.spaceagent.platform.runtime.api.CreateCheckpointCommand;
import com.spaceagent.platform.runtime.api.RuntimeCoordinationApplicationApi;
import com.spaceagent.platform.runtime.api.StartAgentRunCommand;
import com.spaceagent.platform.runtime.api.StartRunStepCommand;
import com.spaceagent.platform.runtime.application.RuntimeApplicationService;
import com.spaceagent.platform.runtime.application.RuntimeCoordinationApplicationService;
import com.spaceagent.platform.runtime.domain.AgentRun;
import com.spaceagent.platform.runtime.domain.ChatPlanReviewCheckpoint;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.RunEventType;
import com.spaceagent.platform.runtime.domain.RunWorkerLeaseClaimType;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationState;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationType;
import com.spaceagent.platform.runtime.infrastructure.persistence.PostgresRuntimeLedgerRepository;
import com.spaceagent.platform.project.api.ChatTaskExecutionReferenceView;
import com.spaceagent.platform.project.api.TaskExecutionApplicationApi;
import com.spaceagent.platform.project.domain.TaskState;
import com.spaceagent.platform.tooling.api.ToolExecutionLedgerApplicationApi;
import com.spaceagent.shared.exception.BusinessException;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
class PlatformRuntimeCoordinationPostgresTest {

    @Test
    void chatPlanReviewCheckpointSurvivesRepositoryRestartByPhase() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        var runtime = new RuntimeApplicationService(
                new PostgresRuntimeLedgerRepository(new JdbcTemplate(dataSource), mapper),
                mock(ToolExecutionLedgerApplicationApi.class), mapper,
                new UuidGenerator(), Instant::now);
        var run = runtime.startRun(new StartAgentRunCommand(
                "owner-1", "agent-1", "00000000-0000-4000-8000-000000000001",
                "conversation-plan-review", null, null));
        runtime.markRunInProgress(run.id());
        var step = runtime.startStep(new StartRunStepCommand(run.id(), "chat-planning"));
        ChatPlanReviewCheckpoint review = new ChatPlanReviewCheckpoint(
                ChatPlanReviewCheckpoint.SCHEMA, "tenant-1", "owner-1", run.id(),
                "conversation-plan-review", "agent-1",
                "00000000-0000-4000-8000-000000000001",
                "00000000-0000-4000-8000-000000000081",
                "00000000-0000-4000-8000-000000000082",
                "reservation-1", 1, "Plan before execution");
        runtime.createCheckpoint(new CreateCheckpointCommand(
                run.id(), mapper.writeValueAsString(Map.of(
                        "phase", "chat-plan-review", "stepId", step.id(),
                        "planReview", review))));

        var restarted = new RuntimeApplicationService(
                new PostgresRuntimeLedgerRepository(new JdbcTemplate(dataSource), mapper),
                mock(ToolExecutionLedgerApplicationApi.class), mapper,
                new UuidGenerator(), Instant::now);
        assertThat(restarted.findLatestCheckpointByPhase(run.id(), "chat-plan-review"))
                .get().extracting(value -> value.stateSnapshot())
                .asString().contains("chat-plan-review/v1", "reservation-1");
    }

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("spaceagent_runtime_coordination")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

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
        Instant now = Instant.now();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO platform_tenants(id,name,slug,status,created_at,updated_at)
                VALUES ('tenant-1','Tenant','tenant','ACTIVE',?,?)
                """, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO platform_users(id,tenant_id,external_id,display_name,created_at,updated_at)
                VALUES ('owner-1','tenant-1','owner@example.com','Owner',?,?)
                """, Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO platform_agent_definitions(
                    id,owner_id,tenant_id,name,description,created_at,updated_at,status,revision)
                VALUES ('agent-1','owner-1','tenant-1','Worker',NULL,?,?,'ACTIVE',1)
                """, Timestamp.from(now), Timestamp.from(now));
    }

    @Test
    void postgresClaimsFenceCasAndAppendEventsAcrossTransactions() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        UuidGenerator ids = new UuidGenerator();
        var repository = new PostgresRuntimeLedgerRepository(
                new JdbcTemplate(dataSource), mapper);
        var runtime = new RuntimeApplicationService(
                repository, mock(ToolExecutionLedgerApplicationApi.class), mapper,
                ids, Instant::now);
        var coordination = new RuntimeCoordinationApplicationService(
                repository, ids, Instant::now, mapper);
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));

        String runId = transaction.execute(ignored -> runtime.startRun(new StartAgentRunCommand(
                "owner-1", "agent-1", "00000000-0000-4000-8000-000000000001",
                "conversation-1", null, null)).id());
        var continuation = transaction.execute(ignored -> coordination.enqueue(
                new RuntimeCoordinationApplicationApi.EnqueueContinuationCommand(
                        runId, RuntimeContinuationType.RESUME_RUN, "resume:postgres", "{}",
                        null, 3)));
        var first = transaction.execute(ignored -> coordination.claimNext(
                new RuntimeCoordinationApplicationApi.ClaimNextContinuationCommand(
                        "worker-a", 30)).orElseThrow());
        transaction.executeWithoutResult(ignored -> {
            runtime.resumeFenced(new ResumeAgentRunCommand(
                    runId, first.lease().leaseToken(), first.lease().fencingToken()));
            coordination.complete(new RuntimeCoordinationApplicationApi.CompleteContinuationCommand(
                    continuation.id(), "worker-a", first.lease().leaseToken(),
                    first.lease().fencingToken()));
        });
        assertThat(coordination.find(continuation.id()).orElseThrow().state())
                .isEqualTo(RuntimeContinuationState.COMPLETED);

        transaction.executeWithoutResult(ignored -> runtime.markRunWaitingForUser(runId));
        var reclaimable = transaction.execute(ignored -> coordination.enqueue(
                new RuntimeCoordinationApplicationApi.EnqueueContinuationCommand(
                        runId, RuntimeContinuationType.RESUME_RUN, "resume:reclaim", "{}",
                        null, 3)));
        var stale = transaction.execute(ignored -> coordination.claimNext(
                new RuntimeCoordinationApplicationApi.ClaimNextContinuationCommand(
                        "worker-a", 30)).orElseThrow());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                UPDATE platform_run_worker_leases
                SET lease_until=clock_timestamp()-INTERVAL '1 second'
                WHERE agent_run_id=?
                """, runId);
        jdbc.update("""
                UPDATE platform_runtime_continuations
                SET lease_until=clock_timestamp()-INTERVAL '1 second'
                WHERE id=CAST(? AS UUID)
                """, reclaimable.id());
        var reclaimed = transaction.execute(ignored -> coordination.claimNext(
                new RuntimeCoordinationApplicationApi.ClaimNextContinuationCommand(
                        "worker-b", 30)).orElseThrow());
        assertThat(reclaimed.lease().fencingToken()).isGreaterThan(stale.lease().fencingToken());
        assertThatThrownBy(() -> transaction.executeWithoutResult(ignored ->
                runtime.resumeFenced(new ResumeAgentRunCommand(
                        runId, stale.lease().leaseToken(), stale.lease().fencingToken()))))
                .isInstanceOf(BusinessException.class);
        transaction.executeWithoutResult(ignored -> {
            runtime.resumeFenced(new ResumeAgentRunCommand(
                    runId, reclaimed.lease().leaseToken(), reclaimed.lease().fencingToken()));
            coordination.complete(new RuntimeCoordinationApplicationApi.CompleteContinuationCommand(
                    reclaimable.id(), "worker-b", reclaimed.lease().leaseToken(),
                    reclaimed.lease().fencingToken()));
        });

        AgentRun expected = repository.findRunById(runId).orElseThrow();
        AgentRun firstUpdate = copy(expected, expected.revision() + 1);
        AgentRun staleUpdate = copy(expected, expected.revision() + 1);
        Boolean firstCas = transaction.execute(ignored ->
                repository.compareAndSetRun(expected, firstUpdate));
        Boolean staleCas = transaction.execute(ignored ->
                repository.compareAndSetRun(expected, staleUpdate));
        assertThat(firstCas).isTrue();
        assertThat(staleCas).isFalse();

        int writers = 8;
        CyclicBarrier barrier = new CyclicBarrier(writers);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(writers)) {
            List<CompletableFuture<Void>> futures = java.util.stream.IntStream.range(0, writers)
                    .mapToObj(index -> CompletableFuture.runAsync(() -> {
                        try {
                            barrier.await(5, TimeUnit.SECONDS);
                            new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                                    .executeWithoutResult(ignored ->
                                            new PostgresRuntimeLedgerRepository(
                                                    new JdbcTemplate(dataSource), mapper)
                                                    .appendEvent(
                                                            runId,
                                                            RunEventType.CURSOR_ADVANCED,
                                                            "{\"writer\":" + index + "}",
                                                            expected.executionCursor(),
                                                            Instant.now()));
                        } catch (Exception error) {
                            throw new IllegalStateException(error);
                        }
                    }, executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(15, TimeUnit.SECONDS);
        }

        var events = repository.findEventsByRunId(runId);
        assertThat(events).extracting(event -> event.sequence())
                .containsExactlyElementsOf(java.util.stream.LongStream.range(0, events.size())
                        .boxed().toList());
        assertThat(jdbc.queryForObject(
                "SELECT MAX(installed_rank) FROM flyway_schema_history", Integer.class))
                .isEqualTo(110);
    }

    @Test
    void twoReplicasCannotClaimTheSameContinuation() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        UuidGenerator ids = new UuidGenerator();
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        var repository = new PostgresRuntimeLedgerRepository(
                new JdbcTemplate(dataSource), mapper);
        var runtime = new RuntimeApplicationService(
                repository, mock(ToolExecutionLedgerApplicationApi.class), mapper,
                ids, Instant::now);
        var coordination = new RuntimeCoordinationApplicationService(
                repository, ids, Instant::now, mapper);
        String runId = transaction.execute(ignored -> runtime.startRun(new StartAgentRunCommand(
                "owner-1", "agent-1", "00000000-0000-4000-8000-000000000001",
                "conversation-claim-race", null, null)).id());
        transaction.executeWithoutResult(ignored -> coordination.enqueue(
                new RuntimeCoordinationApplicationApi.EnqueueContinuationCommand(
                        runId, RuntimeContinuationType.RESUME_RUN, "resume:race", "{}",
                        null, 3)));

        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            List<CompletableFuture<Optional<RuntimeCoordinationApplicationApi.ContinuationClaimView>>>
                    futures = List.of("replica-a", "replica-b").stream()
                    .map(owner -> CompletableFuture.supplyAsync(() -> {
                        try {
                            barrier.await(5, TimeUnit.SECONDS);
                            var localRepository = new PostgresRuntimeLedgerRepository(
                                    new JdbcTemplate(dataSource), mapper);
                            var local = new RuntimeCoordinationApplicationService(
                                    localRepository, new UuidGenerator(), Instant::now, mapper);
                            return new TransactionTemplate(
                                    new DataSourceTransactionManager(dataSource)).execute(ignored ->
                                    local.claimNext(
                                            new RuntimeCoordinationApplicationApi
                                                    .ClaimNextContinuationCommand(owner, 30)));
                        } catch (Exception error) {
                            throw new IllegalStateException(error);
                        }
                    }, executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(15, TimeUnit.SECONDS);
            assertThat(futures.stream().map(CompletableFuture::join).filter(Optional::isPresent))
                    .hasSize(1);
        }
    }

    @Test
    void chatApprovalCheckpointSurvivesRestartAndOnlyOneResumeLeaseWins() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        var repository = new PostgresRuntimeLedgerRepository(
                new JdbcTemplate(dataSource), mapper);
        var runtime = new RuntimeApplicationService(
                repository, mock(ToolExecutionLedgerApplicationApi.class), mapper,
                new UuidGenerator(), Instant::now);
        String runId = transaction.execute(ignored -> runtime.startRun(new StartAgentRunCommand(
                "owner-1", "agent-1", "00000000-0000-4000-8000-000000000001",
                "conversation-chat-approval", null, null)).id());
        transaction.executeWithoutResult(ignored -> {
            runtime.markRunInProgress(runId);
            runtime.markRunWaitingForTool(runId);
            runtime.createCheckpoint(new CreateCheckpointCommand(
                    runId,
                    "{\"phase\":\"chat-approval-waiting\","
                            + "\"approval\":{\"schema\":\"chat-approval/v1\","
                            + "\"pendingApprovalId\":\"approval-1\"}}"));
            runtime.markRunWaitingForUser(runId);
            runtime.createCheckpoint(new CreateCheckpointCommand(
                    runId, "{\"phase\":\"resume-observation\"}"));
        });

        var restartedRepository = new PostgresRuntimeLedgerRepository(
                new JdbcTemplate(dataSource), mapper);
        var restartedRuntime = new RuntimeApplicationService(
                restartedRepository, mock(ToolExecutionLedgerApplicationApi.class), mapper,
                new UuidGenerator(), Instant::now);
        assertThat(restartedRuntime.findRun(runId).orElseThrow().state())
                .isEqualTo(AgentRunState.WAITING_FOR_USER);
        assertThat(restartedRuntime.findLatestCheckpointByPhase(
                        runId, "chat-approval-waiting").orElseThrow().stateSnapshot())
                .contains("chat-approval/v1", "approval-1");

        CyclicBarrier barrier = new CyclicBarrier(2);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            List<CompletableFuture<RuntimeCoordinationApplicationApi.LeaseClaimView>> futures =
                    List.of("chat-resume-a", "chat-resume-b").stream()
                            .map(owner -> CompletableFuture.supplyAsync(() -> {
                                try {
                                    barrier.await(5, TimeUnit.SECONDS);
                                    var local = new RuntimeCoordinationApplicationService(
                                            new PostgresRuntimeLedgerRepository(
                                                    new JdbcTemplate(dataSource), mapper),
                                            new UuidGenerator(), Instant::now, mapper);
                                    return new TransactionTemplate(
                                            new DataSourceTransactionManager(dataSource))
                                            .execute(ignored -> local.acquireLease(
                                                    new RuntimeCoordinationApplicationApi
                                                            .AcquireLeaseCommand(
                                                            runId, owner, 30)));
                                } catch (Exception error) {
                                    throw new IllegalStateException(error);
                                }
                            }, executor))
                            .toList();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(15, TimeUnit.SECONDS);
            assertThat(futures.stream().map(CompletableFuture::join)
                    .filter(value -> value.type() == RunWorkerLeaseClaimType.ACQUIRED))
                    .hasSize(1);
            assertThat(futures.stream().map(CompletableFuture::join)
                    .filter(value -> value.type() == RunWorkerLeaseClaimType.BUSY))
                    .hasSize(1);
        }
    }

    @Test
    void chatTaskPinIsValidatedAndRoundTripsWithTheAgentRun() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        String conversationId = "00000000-0000-4000-8000-000000000091";
        String messageId = "00000000-0000-4000-8000-000000000092";
        String taskId = "00000000-0000-4000-8000-000000000093";
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO platform_conversations(
                    id,tenant_id,user_id,title,status,created_at,updated_at)
                VALUES(?,?,?,'Pinned Chat','ACTIVE',?,?)
                """, conversationId, "tenant-1", "owner-1",
                Timestamp.from(now), Timestamp.from(now));
        jdbc.update("""
                INSERT INTO platform_messages(
                    id,conversation_id,sequence_number,role,content,created_at)
                VALUES(?,?,0,'USER','Pinned goal',?)
                """, messageId, conversationId, Timestamp.from(now));
        jdbc.update("""
                INSERT INTO platform_tasks(
                    id,tenant_id,owner_user_id,conversation_id,source_message_id,
                    title,goal,state,created_at,updated_at)
                VALUES(CAST(? AS UUID),?,?,?,?,'Pinned goal','Pinned goal','IN_PROGRESS',?,?)
                """, taskId, "tenant-1", "owner-1", conversationId, messageId,
                Timestamp.from(now), Timestamp.from(now));
        TaskExecutionApplicationApi taskExecution = mock(TaskExecutionApplicationApi.class);
        when(taskExecution.resolveChatTask(any())).thenReturn(
                new ChatTaskExecutionReferenceView(
                        taskId, conversationId, messageId, TaskState.IN_PROGRESS));
        ObjectMapper mapper = new ObjectMapper();
        var runtime = new RuntimeApplicationService(
                new PostgresRuntimeLedgerRepository(jdbc, mapper),
                mock(ToolExecutionLedgerApplicationApi.class), mapper,
                new UuidGenerator(), Instant::now, null, null, taskExecution);

        var run = runtime.startRun(new StartAgentRunCommand(
                "tenant-1", "owner-1", "agent-1",
                "00000000-0000-4000-8000-000000000001", conversationId,
                taskId, null, null, null, null, null, null));

        assertThat(run.chatTaskId()).isEqualTo(taskId);
        assertThat(runtime.findRun(run.id()).orElseThrow().chatTaskId()).isEqualTo(taskId);
        assertThatThrownBy(() -> new StartAgentRunCommand(
                "tenant-1", "owner-1", "agent-1",
                "00000000-0000-4000-8000-000000000001", conversationId,
                taskId, "00000000-0000-4000-8000-000000000094",
                null, null, "00000000-0000-4000-8000-000000000095",
                "00000000-0000-4000-8000-000000000096",
                "00000000-0000-4000-8000-000000000097"))
                .isInstanceOf(IllegalArgumentException.class);
        jdbc.update("DELETE FROM platform_conversations WHERE id=?", conversationId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_tasks WHERE id=CAST(? AS UUID)",
                Long.class, taskId)).isZero();
        assertThat(runtime.findRun(run.id()).orElseThrow().chatTaskId()).isNull();
    }

    private static AgentRun copy(AgentRun run, long revision) {
        return new AgentRun(
                run.id(), run.agentId(), run.configurationSnapshotId(), run.tenantId(), run.ownerId(),
                run.conversationId(), run.projectId(), run.taskId(), run.taskPlanId(),
                run.planStepId(), run.executionCursor(), revision, run.state(),
                run.failureReason(), run.createdAt(), Instant.now(), run.completedAt());
    }
}
