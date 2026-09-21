package com.spaceagent.platform.inference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.inference.api.InferenceExecutionApi;
import com.spaceagent.platform.inference.application.InferenceExecutionService;
import com.spaceagent.platform.inference.application.ModelCallLedgerService;
import com.spaceagent.platform.inference.application.ModelCallPayloadCodec;
import com.spaceagent.platform.inference.application.ModelCallRequestHasher;
import com.spaceagent.platform.inference.domain.InferenceExecutor;
import com.spaceagent.platform.inference.domain.ModelCallClaimDecision;
import com.spaceagent.platform.inference.domain.ModelCallClaimDecisionType;
import com.spaceagent.platform.inference.domain.ModelCallClaimRequest;
import com.spaceagent.platform.inference.domain.ModelCallCompletionRequest;
import com.spaceagent.platform.inference.domain.ModelCallFirstChunkRequest;
import com.spaceagent.platform.inference.domain.ModelCallLeasePolicy;
import com.spaceagent.platform.inference.domain.ModelCallLedger;
import com.spaceagent.platform.inference.domain.ModelCallStatus;
import com.spaceagent.platform.inference.domain.ModelCallTransitionType;
import com.spaceagent.platform.inference.infrastructure.persistence.PostgresModelCallLedgerRepository;
import com.spaceagent.shared.exception.BusinessException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PlatformModelCallLedgerPostgresTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg17")
                    .withDatabaseName("model_call_ledger")
                    .withUsername("spaceagent")
                    .withPassword("spaceagent");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        dataSource = source;
        jdbc = new JdbcTemplate(source);
        Flyway flyway = Flyway.configure()
                .dataSource(source)
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .cleanDisabled(false)
                .load();
        flyway.clean();
        flyway.migrate();
    }

    @AfterAll
    static void clearReferences() {
        jdbc = null;
        dataSource = null;
    }

    @BeforeEach
    void resetDatabase() {
        jdbc.update("DELETE FROM platform_model_call_ledger");
        jdbc.update("DELETE FROM platform_run_steps");
        jdbc.update("DELETE FROM platform_agent_runs");
    }

    @Test
    void twoRepositoryInstancesAtomicallyReturnClaimedAndBusy() throws Exception {
        RuntimeIds ids = runtimeIds();
        PostgresModelCallLedgerRepository first = repository();
        PostgresModelCallLedgerRepository second = repository();
        CyclicBarrier barrier = new CyclicBarrier(2);

        CompletableFuture<ModelCallClaimDecision> left = CompletableFuture.supplyAsync(() -> {
            await(barrier);
            return first.claim(claim(ids, "hash-a", "token-a", 30));
        });
        CompletableFuture<ModelCallClaimDecision> right = CompletableFuture.supplyAsync(() -> {
            await(barrier);
            return second.claim(claim(ids, "hash-a", "token-b", 30));
        });

        assertThat(List.of(left.get(10, TimeUnit.SECONDS).type(), right.get(10, TimeUnit.SECONDS).type()))
                .containsExactlyInAnyOrder(
                        ModelCallClaimDecisionType.CLAIMED,
                        ModelCallClaimDecisionType.BUSY);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM platform_model_call_ledger WHERE agent_run_id = ?",
                Integer.class,
                ids.runId())).isEqualTo(1);
    }

    @Test
    void twoPlatformStyleServicesShareDatabaseAndInvokeProviderOnce() throws Exception {
        RuntimeIds ids = runtimeIds();
        AtomicInteger providerCalls = new AtomicInteger();
        CountDownLatch providerEntered = new CountDownLatch(1);
        CountDownLatch releaseProvider = new CountDownLatch(1);
        InferenceExecutor executor = request -> {
            providerCalls.incrementAndGet();
            providerEntered.countDown();
            await(releaseProvider);
            return new InferenceExecutor.InferenceExecution("shared result", 2, 1);
        };
        InferenceExecutionService first = inferenceService(repository(), executor, "instance-a");
        InferenceExecutionService second = inferenceService(repository(), executor, "instance-b");
        InferenceExecutionApi.InferenceExecutionCommand command = command(ids, "same request");

        CompletableFuture<InferenceExecutionApi.InferenceExecutionResult> running =
                CompletableFuture.supplyAsync(() -> first.execute(command));
        assertThat(providerEntered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> second.execute(command))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("MODEL_CALL_IN_PROGRESS"));
        releaseProvider.countDown();

        assertThat(running.get(5, TimeUnit.SECONDS).content()).isEqualTo("shared result");
        assertThat(second.execute(command).content()).isEqualTo("shared result");
        assertThat(providerCalls).hasValue(1);
    }

    @Test
    void differentHashConflictsWithoutOverwritingOriginalHash() {
        RuntimeIds ids = runtimeIds();
        PostgresModelCallLedgerRepository repository = repository();
        repository.claim(claim(ids, "original-hash", "token-a", 30));

        ModelCallClaimDecision conflict = repository.claim(claim(ids, "changed-hash", "token-b", 30));

        assertThat(conflict.type()).isEqualTo(ModelCallClaimDecisionType.CONFLICT);
        assertThat(repository.findByRunIdAndLogicalCallId(ids.runId(), ids.logicalCallId())
                .orElseThrow().requestHash()).isEqualTo("original-hash");
    }

    @Test
    void firstChunkUsesDatabaseClockAndActiveClaimExactlyOnce() {
        RuntimeIds ids = runtimeIds();
        PostgresModelCallLedgerRepository repository = repository();
        ModelCallClaimDecision claim = repository.claim(claim(ids, "stream-hash", "token-a", 30));

        var first = repository.recordFirstChunk(new ModelCallFirstChunkRequest(
                ids.runId(), ids.logicalCallId(), claim.ledger().claimToken(), claim.ledger().revision(), 125));
        var replay = repository.recordFirstChunk(new ModelCallFirstChunkRequest(
                ids.runId(), ids.logicalCallId(), claim.ledger().claimToken(), claim.ledger().revision(), 999));
        var stale = repository.recordFirstChunk(new ModelCallFirstChunkRequest(
                ids.runId(), ids.logicalCallId(), UUID.randomUUID().toString(),
                claim.ledger().revision(), 250));

        assertThat(first.type()).isEqualTo(ModelCallTransitionType.APPLIED);
        assertThat(first.ledger().firstChunkAt()).isNotNull();
        assertThat(first.ledger().firstChunkMillis()).isEqualTo(125L);
        assertThat(replay.ledger().firstChunkAt()).isEqualTo(first.ledger().firstChunkAt());
        assertThat(stale.type()).isEqualTo(ModelCallTransitionType.CLAIM_LOST);
    }

    @Test
    void v1048UpgradesV1047WithoutInventingFirstChunkEvidence() {
        String schema = "model_first_chunk_upgrade";
        Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime")
                .target(org.flywaydb.core.api.MigrationVersion.fromVersion("1047"))
                .load().migrate();
        DriverManagerDataSource scoped =
                com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema);
        JdbcTemplate scopedJdbc = new JdbcTemplate(scoped);
        assertThat(scopedJdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema=? AND table_name='platform_model_call_ledger'
                   AND column_name='first_chunk_at'
                """, Long.class, "public")).isZero();

        Flyway.configure().dataSource(com.spaceagent.platform.support.PostgresSchemaDataSource.forSchema(POSTGRES, schema))
                .locations("classpath:db/platform-server", "classpath:db/platform-runtime").load().migrate();

        assertThat(scopedJdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_schema=? AND table_name='platform_model_call_ledger'
                   AND column_name IN('first_chunk_at','first_chunk_ms')
                """, Long.class, "public")).isEqualTo(2L);
        assertThat(scopedJdbc.queryForObject("""
                SELECT version FROM flyway_schema_history
                 WHERE success ORDER BY installed_rank DESC LIMIT 1
                """, String.class)).isEqualTo("1098");
    }

    @Test
    void activeRunningIsBusyAndExpiredRunningBecomesUnknown() {
        RuntimeIds activeIds = runtimeIds();
        RuntimeIds expiredIds = runtimeIds();
        PostgresModelCallLedgerRepository repository = repository();
        repository.claim(claim(activeIds, "active-hash", "token-a", 30));
        repository.claim(claim(expiredIds, "expired-hash", "token-b", 30));

        assertThat(repository.claim(claim(activeIds, "active-hash", "token-c", 30)).type())
                .isEqualTo(ModelCallClaimDecisionType.BUSY);
        jdbc.update(
                "UPDATE platform_model_call_ledger SET lease_until = clock_timestamp() - INTERVAL '1 second' "
                        + "WHERE agent_run_id = ? AND logical_call_id = ?",
                expiredIds.runId(),
                expiredIds.logicalCallId());
        ModelCallClaimDecision expired = repository.claim(
                claim(expiredIds, "expired-hash", "token-d", 30));

        assertThat(expired.type()).isEqualTo(ModelCallClaimDecisionType.UNKNOWN);
        assertThat(expired.ledger().status()).isEqualTo(ModelCallStatus.UNKNOWN);
        assertThat(repository.claim(claim(expiredIds, "expired-hash", "token-e", 30)).type())
                .isEqualTo(ModelCallClaimDecisionType.UNKNOWN);
    }

    @Test
    void wrongTokenAndTrulyStaleRevisionCannotComplete() {
        RuntimeIds ids = runtimeIds();
        PostgresModelCallLedgerRepository repository = repository();
        ModelCallClaimDecision claim = repository.claim(claim(ids, "hash", "right-token", 30));

        assertThat(repository.complete(completion(ids, "wrong-token", claim.ledger().revision())).type())
                .isEqualTo(ModelCallTransitionType.CLAIM_LOST);
        jdbc.update(
                "UPDATE platform_model_call_ledger SET revision = revision + 1 WHERE agent_run_id = ? "
                        + "AND logical_call_id = ?",
                ids.runId(),
                ids.logicalCallId());
        assertThat(repository.complete(completion(ids, "right-token", claim.ledger().revision())).type())
                .isEqualTo(ModelCallTransitionType.CLAIM_LOST);
        assertThat(repository.findByRunIdAndLogicalCallId(ids.runId(), ids.logicalCallId())
                .orElseThrow().status()).isEqualTo(ModelCallStatus.RUNNING);
    }

    @Test
    void successfulTerminalPersistsStandardResponseUsageAndProviderRequestIdForReplay() {
        RuntimeIds ids = runtimeIds();
        PostgresModelCallLedgerRepository repository = repository();
        ModelCallClaimDecision claim = repository.claim(claim(ids, "hash", "token", 30));
        ModelCallCompletionRequest completion = new ModelCallCompletionRequest(
                ids.runId(),
                ids.logicalCallId(),
                uuidToken("token"),
                claim.ledger().revision(),
                ModelCallStatus.SUCCEEDED,
                "provider-request-9",
                "{\"content\":\"answer\",\"finishReason\":\"stop\",\"toolCalls\":[]}",
                "{\"inputTokens\":7,\"outputTokens\":3,\"totalTokens\":10}",
                null,
                null);

        assertThat(repository.complete(completion).type()).isEqualTo(ModelCallTransitionType.APPLIED);
        ModelCallClaimDecision replay = repository.claim(claim(ids, "hash", "other-token", 30));

        assertThat(replay.type()).isEqualTo(ModelCallClaimDecisionType.REPLAY);
        assertThat(replay.ledger().providerRequestId()).isEqualTo("provider-request-9");
        assertThat(replay.ledger().responsePayload()).contains("answer", "finishReason", "toolCalls");
        assertThat(replay.ledger().usagePayload()).contains("inputTokens", "totalTokens");
    }

    @Test
    void migrationUsesTimestamptzAndRejectsRunningWithoutClaimMetadata() {
        RuntimeIds ids = runtimeIds();
        assertThat(jdbc.queryForObject("""
                SELECT data_type
                  FROM information_schema.columns
                 WHERE table_name = 'platform_model_call_ledger'
                   AND column_name = 'lease_until'
                """, String.class)).isEqualTo("timestamp with time zone");

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO platform_model_call_ledger (
                    id, agent_run_id, run_step_id, logical_call_id, request_hash,
                    status, provider_id, model_id, revision, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'RUNNING', ?, ?, 1, clock_timestamp(), clock_timestamp())
                """,
                UUID.randomUUID().toString(),
                ids.runId(),
                ids.stepId(),
                "invalid-running",
                "hash",
                "provider",
                "model"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static PostgresModelCallLedgerRepository repository() {
        return new PostgresModelCallLedgerRepository(
                new JdbcTemplate(dataSource),
                new DataSourceTransactionManager(dataSource));
    }

    private static InferenceExecutionService inferenceService(
            PostgresModelCallLedgerRepository repository,
            InferenceExecutor executor,
            String owner) {
        ObjectMapper mapper = new ObjectMapper();
        ModelCallLedgerService ledger = new ModelCallLedgerService(
                repository,
                () -> UUID.randomUUID().toString(),
                () -> owner);
        ModelCallLeasePolicy lease = new ModelCallLeasePolicy() {
            @Override
            public long requestTimeoutSeconds() {
                return 20;
            }

            @Override
            public long claimLeaseSeconds() {
                return 30;
            }
        };
        return new InferenceExecutionService(
                executor,
                ledger,
                new ModelCallRequestHasher(mapper),
                new ModelCallPayloadCodec(mapper),
                lease, null, com.spaceagent.platform.inference.domain.InferenceTelemetry.noop(), (t, r) -> {});
    }

    private static RuntimeIds runtimeIds() {
        String runId = UUID.randomUUID().toString();
        String stepId = UUID.randomUUID().toString();
        String agentId = UUID.randomUUID().toString();
        String ownerId = UUID.randomUUID().toString();
        java.sql.Timestamp now = java.sql.Timestamp.from(Instant.now());
        jdbc.update("""
                INSERT INTO platform_agent_definitions (
                    id, owner_id, tenant_id, name, description, status,
                    revision, created_at, updated_at, archived_at
                ) VALUES (?, ?, 'tenant-model-ledger', ?, NULL, 'ACTIVE', 1, ?, ?, NULL)
                """, agentId, ownerId, "model-ledger-" + agentId, now, now);
        jdbc.update("""
                INSERT INTO platform_agent_runs (
                    id, agent_id, owner_id, conversation_id, state, created_at, updated_at
                ) VALUES (?, ?, ?, ?, 'IN_PROGRESS', ?, ?)
                """,
                runId,
                agentId,
                ownerId,
                UUID.randomUUID().toString(),
                now,
                now);
        jdbc.update("""
                INSERT INTO platform_run_steps (id, agent_run_id, sequence, type, state, created_at)
                VALUES (?, ?, 1, 'inference', 'RUNNING', ?)
                """,
                stepId,
                runId,
                java.sql.Timestamp.from(Instant.now()));
        return new RuntimeIds(runId, stepId, stepId + ":inference:0");
    }

    private static ModelCallClaimRequest claim(
            RuntimeIds ids,
            String requestHash,
            String token,
            long leaseSeconds) {
        return new ModelCallClaimRequest(
                UUID.randomUUID().toString(),
                ids.runId(),
                ids.stepId(),
                ids.logicalCallId(),
                requestHash,
                "provider",
                "model",
                uuidToken(token),
                "test-instance",
                leaseSeconds);
    }

    private static ModelCallCompletionRequest completion(
            RuntimeIds ids,
            String token,
            long revision) {
        return new ModelCallCompletionRequest(
                ids.runId(),
                ids.logicalCallId(),
                uuidToken(token),
                revision,
                ModelCallStatus.SUCCEEDED,
                "provider-request",
                "{\"content\":\"ok\",\"toolCalls\":[]}",
                "{\"inputTokens\":1,\"outputTokens\":1,\"totalTokens\":2}",
                null,
                null);
    }

    private static InferenceExecutionApi.InferenceExecutionCommand command(RuntimeIds ids, String message) {
        return new InferenceExecutionApi.InferenceExecutionCommand(
                "provider",
                "model",
                List.of(new InferenceExecutionApi.InferenceMessage("user", message)),
                Map.of("temperature", 0.1, "maxOutputTokens", 20),
                ids.runId(),
                ids.stepId(),
                ids.logicalCallId());
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String uuidToken(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for test latch");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private record RuntimeIds(String runId, String stepId, String logicalCallId) {
    }
}
