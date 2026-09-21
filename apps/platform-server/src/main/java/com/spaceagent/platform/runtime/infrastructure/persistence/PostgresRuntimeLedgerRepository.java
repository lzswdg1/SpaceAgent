package com.spaceagent.platform.runtime.infrastructure.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.runtime.domain.AgentRun;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.Checkpoint;
import com.spaceagent.platform.runtime.domain.Handoff;
import com.spaceagent.platform.runtime.domain.HandoffSnapshot;
import com.spaceagent.platform.runtime.domain.HandoffState;
import com.spaceagent.platform.runtime.domain.HandoffTestStatus;
import com.spaceagent.platform.runtime.domain.Recovery;
import com.spaceagent.platform.runtime.domain.RecoveryState;
import com.spaceagent.platform.runtime.domain.RunStep;
import com.spaceagent.platform.runtime.domain.RunEvent;
import com.spaceagent.platform.runtime.domain.RunEventType;
import com.spaceagent.platform.runtime.domain.RunStepState;
import com.spaceagent.platform.runtime.domain.RunWorkerLease;
import com.spaceagent.platform.runtime.domain.RunWorkerLeaseClaim;
import com.spaceagent.platform.runtime.domain.RunWorkerLeaseClaimType;
import com.spaceagent.platform.runtime.domain.RuntimeContinuation;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationClaim;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationState;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationType;
import com.spaceagent.platform.runtime.domain.RuntimeLedgerRepository;
import com.spaceagent.platform.runtime.domain.orchestration.ExecutionCursor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Authoritative PostgreSQL-backed runtime run/step/checkpoint/recovery ledger.
 */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresRuntimeLedgerRepository implements RuntimeLedgerRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PostgresRuntimeLedgerRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<AgentRun> findRunById(String agentRunId) {
        return jdbcTemplate.query("""
                SELECT id, agent_id, tenant_id, owner_id, conversation_id,
                       chat_task_id,
                       COALESCE(project_uuid::text, project_id) AS project_id,
                       project_directory_id, workspace_id,
                       COALESCE(task_uuid::text, task_id) AS task_id,
                       task_plan_id, plan_step_id, execution_cursor::text AS execution_cursor,
                       revision, state, failure_reason, created_at, updated_at, completed_at
                FROM platform_agent_runs
                WHERE id = ?
                """, this::mapRun, agentRunId).stream().findFirst();
    }

    @Override
    public List<RunStep> findStepsByRunId(String agentRunId) {
        return jdbcTemplate.query("""
                SELECT id, agent_run_id, sequence, type, state, created_at, completed_at
                FROM platform_run_steps
                WHERE agent_run_id = ?
                ORDER BY sequence
                """, this::mapStep, agentRunId);
    }

    @Override public Optional<AgentRun> findLatestChatRun(String tenantId,String ownerId,String conversationId){
        return jdbcTemplate.query("""
                SELECT id,agent_id,tenant_id,owner_id,conversation_id,chat_task_id,
                COALESCE(project_uuid::text,project_id) AS project_id,project_directory_id,workspace_id,
                COALESCE(task_uuid::text,task_id) AS task_id,task_plan_id,plan_step_id,
                execution_cursor::text AS execution_cursor,revision,state,failure_reason,created_at,updated_at,completed_at
                FROM platform_agent_runs WHERE tenant_id=? AND owner_id=? AND conversation_id=?
                AND project_id IS NULL AND project_uuid IS NULL ORDER BY created_at DESC,id DESC LIMIT 1
                """,this::mapRun,tenantId,ownerId,conversationId).stream().findFirst();
    }

    @Override
    public List<RunEvent> findEventsByRunId(String agentRunId) {
        return findEventsByRunIdAfter(agentRunId, -1, Integer.MAX_VALUE);
    }

    @Override
    public List<RunEvent> findEventsByRunIdAfter(
            String agentRunId,
            long afterSequence,
            int limit) {
        return jdbcTemplate.query("""
                SELECT id, agent_run_id, sequence_number, event_type,
                       payload::text AS payload, execution_cursor::text AS execution_cursor,
                       created_at
                FROM platform_run_events
                WHERE agent_run_id = ? AND sequence_number > ?
                ORDER BY sequence_number
                LIMIT ?
                """, this::mapEvent, agentRunId, afterSequence, limit);
    }

    @Override
    public long findLatestEventSequence(String agentRunId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sequence_number), -1) FROM platform_run_events "
                        + "WHERE agent_run_id = ?",
                Long.class, agentRunId);
        return value == null ? -1L : value;
    }

    @Override
    public Optional<RunStep> findStepById(String runStepId) {
        return jdbcTemplate.query("""
                SELECT id, agent_run_id, sequence, type, state, created_at, completed_at
                FROM platform_run_steps
                WHERE id = ?
                """, this::mapStep, runStepId).stream().findFirst();
    }

    @Override
    public Optional<Checkpoint> findLatestCheckpoint(String agentRunId) {
        return jdbcTemplate.query("""
                SELECT id, agent_run_id, sequence, state_snapshot, created_at
                FROM platform_run_checkpoints
                WHERE agent_run_id = ?
                ORDER BY sequence DESC
                LIMIT 1
                """, this::mapCheckpoint, agentRunId).stream().findFirst();
    }

    @Override
    public Optional<Checkpoint> findLatestCheckpointByPhase(String agentRunId, String phase) {
        return jdbcTemplate.query("""
                SELECT id, agent_run_id, sequence, state_snapshot, created_at
                FROM platform_run_checkpoints
                WHERE agent_run_id = ?
                  AND state_snapshot::jsonb ->> 'phase' = ?
                ORDER BY sequence DESC
                LIMIT 1
                """, this::mapCheckpoint, agentRunId, phase).stream().findFirst();
    }

    @Override
    public List<Recovery> findRecoveriesByRunId(String agentRunId) {
        return jdbcTemplate.query("""
                SELECT id, agent_run_id, attempt, state, reason, created_at, completed_at
                FROM platform_run_recoveries
                WHERE agent_run_id = ?
                ORDER BY attempt
                """, this::mapRecovery, agentRunId);
    }

    @Override
    public void createRun(AgentRun run) {
        jdbcTemplate.update("""
                INSERT INTO platform_agent_runs (
                    id, agent_id, tenant_id, owner_id, conversation_id,
                    chat_task_id,
                    project_id, task_id, project_uuid, project_directory_id, workspace_id,
                    task_uuid, task_plan_id, plan_step_id,
                    execution_cursor, revision, state, failure_reason,
                    created_at, updated_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, CAST(? AS UUID), ?, ?, CAST(? AS UUID), CAST(? AS UUID),
                          CAST(? AS UUID), CAST(? AS UUID), CAST(? AS UUID), CAST(? AS UUID),
                          CAST(? AS JSONB), ?, ?, ?, ?, ?, ?)
                """,
                run.id(), run.agentId(), run.tenantId(), run.ownerId(),
                run.conversationId(), uuid(run.chatTaskId()),
                run.projectId(), run.taskId(),
                canonicalUuid(run, run.projectId()), canonicalUuid(run, run.projectDirectoryId()),
                canonicalUuid(run, run.workspaceId()), canonicalUuid(run, run.taskId()),
                canonicalUuid(run, run.taskPlanId()), canonicalUuid(run, run.planStepId()),
                writeCursor(run.executionCursor()), run.revision(),
                run.state().name(), run.failureReason(),
                timestamp(run.createdAt()), timestamp(run.updatedAt()),
                timestamp(run.completedAt()));
    }

    @Override
    public boolean compareAndSetRun(AgentRun expected, AgentRun updated) {
        requireNextRevision(expected, updated);
        return updateRun(expected, updated, null, 0) == 1;
    }

    @Override
    public boolean compareAndSetRunFenced(
            AgentRun expected,
            AgentRun updated,
            String leaseToken,
            long fencingToken) {
        requireNextRevision(expected, updated);
        return updateRun(expected, updated, leaseToken, fencingToken) == 1;
    }

    private int updateRun(
            AgentRun expected,
            AgentRun updated,
            String leaseToken,
            long fencingToken) {
        String fenceClause = leaseToken == null ? "" : """
                AND EXISTS (
                    SELECT 1 FROM platform_run_worker_leases lease
                    WHERE lease.agent_run_id = platform_agent_runs.id
                      AND lease.lease_token = CAST(? AS UUID)
                      AND lease.fencing_token = ?
                      AND lease.released_at IS NULL
                      AND lease.lease_until > clock_timestamp())
                """;
        String sql = """
                UPDATE platform_agent_runs
                SET execution_cursor = CAST(? AS JSONB),
                    revision = ?, state = ?, failure_reason = ?,
                    updated_at = ?, completed_at = ?
                WHERE id = ? AND revision = ?
                """ + fenceClause;
        Object[] base = {
                writeCursor(updated.executionCursor()),
                updated.revision(),
                updated.state().name(),
                updated.failureReason(),
                timestamp(updated.updatedAt()),
                timestamp(updated.completedAt()),
                expected.id(),
                expected.revision()
        };
        if (leaseToken == null) {
            return jdbcTemplate.update(sql, base);
        }
        Object[] fenced = java.util.Arrays.copyOf(base, base.length + 2);
        fenced[base.length] = leaseToken;
        fenced[base.length + 1] = fencingToken;
        return jdbcTemplate.update(sql, fenced);
    }

    @Override
    public void saveStep(RunStep step) {
        jdbcTemplate.update("""
                INSERT INTO platform_run_steps (
                    id, agent_run_id, sequence, type, state, created_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    agent_run_id = EXCLUDED.agent_run_id,
                    sequence = EXCLUDED.sequence,
                    type = EXCLUDED.type,
                    state = EXCLUDED.state,
                    completed_at = EXCLUDED.completed_at
                """,
                step.id(), step.agentRunId(), step.sequence(), step.type(),
                step.state().name(), timestamp(step.createdAt()), timestamp(step.completedAt()));
    }

    @Override
    public void saveCheckpoint(Checkpoint checkpoint) {
        jdbcTemplate.update("""
                INSERT INTO platform_run_checkpoints (
                    id, agent_run_id, sequence, state_snapshot, created_at
                ) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    agent_run_id = EXCLUDED.agent_run_id,
                    sequence = EXCLUDED.sequence,
                    state_snapshot = EXCLUDED.state_snapshot,
                    created_at = EXCLUDED.created_at
                """,
                checkpoint.id(), checkpoint.agentRunId(), checkpoint.sequence(),
                checkpoint.stateSnapshot(), timestamp(checkpoint.createdAt()));
    }

    @Override
    public void saveRecovery(Recovery recovery) {
        jdbcTemplate.update("""
                INSERT INTO platform_run_recoveries (
                    id, agent_run_id, attempt, state, reason, created_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    agent_run_id = EXCLUDED.agent_run_id,
                    attempt = EXCLUDED.attempt,
                    state = EXCLUDED.state,
                    reason = EXCLUDED.reason,
                    completed_at = EXCLUDED.completed_at
                """,
                recovery.id(), recovery.agentRunId(), recovery.attempt(),
                recovery.state().name(), recovery.reason(),
                timestamp(recovery.createdAt()), timestamp(recovery.completedAt()));
    }

    @Override
    public void saveHandoff(Handoff handoff) {
        HandoffSnapshot snapshot = handoff.snapshot();
        jdbcTemplate.update("""
                INSERT INTO platform_run_handoffs (
                    id, source_agent_run_id, target_agent_run_id, state, goal,
                    current_state, completed_work, decisions, failed_attempts,
                    changed_files, test_status, blockers, next_actions,
                    created_at, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                    source_agent_run_id = EXCLUDED.source_agent_run_id,
                    target_agent_run_id = EXCLUDED.target_agent_run_id,
                    state = EXCLUDED.state,
                    goal = EXCLUDED.goal,
                    current_state = EXCLUDED.current_state,
                    completed_work = EXCLUDED.completed_work,
                    decisions = EXCLUDED.decisions,
                    failed_attempts = EXCLUDED.failed_attempts,
                    changed_files = EXCLUDED.changed_files,
                    test_status = EXCLUDED.test_status,
                    blockers = EXCLUDED.blockers,
                    next_actions = EXCLUDED.next_actions,
                    completed_at = EXCLUDED.completed_at
                """,
                handoff.id(), handoff.sourceAgentRunId(), handoff.targetAgentRunId(),
                handoff.state().name(), snapshot.goal(), snapshot.currentState(),
                writeList(snapshot.completedWork()), writeList(snapshot.decisions()),
                writeList(snapshot.failedAttempts()), writeList(snapshot.changedFiles()),
                snapshot.testStatus().name(), writeList(snapshot.blockers()),
                writeList(snapshot.nextActions()), timestamp(handoff.createdAt()),
                timestamp(handoff.completedAt()));
    }

    @Override
    public RunEvent appendEvent(
            String agentRunId,
            RunEventType type,
            String payload,
            ExecutionCursor cursor,
            Instant createdAt) {
        jdbcTemplate.queryForObject(
                "SELECT id FROM platform_agent_runs WHERE id = ? FOR UPDATE",
                String.class,
                agentRunId);
        Long next = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(sequence_number), -1) + 1
                FROM platform_run_events
                WHERE agent_run_id = ?
                """, Long.class, agentRunId);
        long sequence = next == null ? 0L : next;
        RunEvent event = new RunEvent(
                agentRunId + ":event:" + sequence,
                agentRunId,
                sequence,
                type,
                payload,
                cursor,
                createdAt);
        jdbcTemplate.update("""
                INSERT INTO platform_run_events (
                    id, agent_run_id, sequence_number, event_type,
                    payload, execution_cursor, created_at
                ) VALUES (?, ?, ?, ?, CAST(? AS JSONB), CAST(? AS JSONB), ?)
                """,
                event.id(), event.agentRunId(), event.sequence(), event.type().name(),
                event.payload(), writeCursor(event.cursor()), timestamp(event.createdAt()));
        return event;
    }

    @Override
    public Optional<Handoff> findHandoffById(String handoffId) {
        return jdbcTemplate.query("""
                SELECT id, source_agent_run_id, target_agent_run_id, state, goal,
                       current_state, completed_work, decisions, failed_attempts,
                       changed_files, test_status, blockers, next_actions,
                       created_at, completed_at
                FROM platform_run_handoffs
                WHERE id = ?
                """, this::mapHandoff, handoffId).stream().findFirst();
    }

    @Override
    public List<Handoff> findHandoffsBySourceRunId(String sourceAgentRunId) {
        return jdbcTemplate.query("""
                SELECT id, source_agent_run_id, target_agent_run_id, state, goal,
                       current_state, completed_work, decisions, failed_attempts,
                       changed_files, test_status, blockers, next_actions,
                       created_at, completed_at
                FROM platform_run_handoffs
                WHERE source_agent_run_id = ?
                ORDER BY created_at, id
                """, this::mapHandoff, sourceAgentRunId);
    }

    @Override
    public Optional<RunWorkerLease> findWorkerLease(String agentRunId) {
        return jdbcTemplate.query("""
                SELECT agent_run_id, lease_token, lease_owner, fencing_token, lease_until,
                       revision, acquired_at, heartbeat_at, released_at
                FROM platform_run_worker_leases
                WHERE agent_run_id = ?
                """, this::mapLease, agentRunId).stream().findFirst();
    }

    @Override
    public RunWorkerLeaseClaim claimWorkerLease(
            String agentRunId,
            String leaseOwner,
            String leaseToken,
            int leaseSeconds) {
        lockNonTerminalRun(agentRunId);
        List<RunWorkerLease> acquired = jdbcTemplate.query("""
                INSERT INTO platform_run_worker_leases(
                    agent_run_id, lease_token, lease_owner, fencing_token, lease_until,
                    revision, acquired_at, heartbeat_at, released_at)
                VALUES (?, CAST(? AS UUID), ?, 1,
                        clock_timestamp() + (? * INTERVAL '1 second'),
                        1, clock_timestamp(), clock_timestamp(), NULL)
                ON CONFLICT(agent_run_id) DO UPDATE SET
                    lease_token = EXCLUDED.lease_token,
                    lease_owner = EXCLUDED.lease_owner,
                    fencing_token = platform_run_worker_leases.fencing_token + 1,
                    lease_until = EXCLUDED.lease_until,
                    revision = platform_run_worker_leases.revision + 1,
                    acquired_at = clock_timestamp(),
                    heartbeat_at = clock_timestamp(),
                    released_at = NULL
                WHERE platform_run_worker_leases.released_at IS NOT NULL
                   OR platform_run_worker_leases.lease_until <= clock_timestamp()
                RETURNING agent_run_id, lease_token, lease_owner, fencing_token,
                          lease_until, revision, acquired_at, heartbeat_at, released_at
                """, this::mapLease, agentRunId, leaseToken, leaseOwner, leaseSeconds);
        if (!acquired.isEmpty()) {
            return new RunWorkerLeaseClaim(
                    RunWorkerLeaseClaimType.ACQUIRED, acquired.getFirst(), null);
        }
        RunWorkerLease current = findWorkerLease(agentRunId)
                .orElseThrow(() -> new IllegalStateException("Worker lease conflict without row"));
        return new RunWorkerLeaseClaim(
                RunWorkerLeaseClaimType.BUSY, current, "Run worker lease is active");
    }

    @Override
    public Optional<RunWorkerLease> heartbeatWorkerLease(
            String agentRunId,
            String leaseOwner,
            String leaseToken,
            long fencingToken,
            int leaseSeconds) {
        return jdbcTemplate.query("""
                UPDATE platform_run_worker_leases
                SET lease_until = clock_timestamp() + (? * INTERVAL '1 second'),
                    heartbeat_at = clock_timestamp(), revision = revision + 1
                WHERE agent_run_id = ?
                  AND lease_owner = ?
                  AND lease_token = CAST(? AS UUID)
                  AND fencing_token = ?
                  AND released_at IS NULL
                  AND lease_until > clock_timestamp()
                RETURNING agent_run_id, lease_token, lease_owner, fencing_token,
                          lease_until, revision, acquired_at, heartbeat_at, released_at
                """, this::mapLease, leaseSeconds, agentRunId, leaseOwner,
                leaseToken, fencingToken).stream().findFirst();
    }

    @Override
    public boolean releaseWorkerLease(
            String agentRunId,
            String leaseOwner,
            String leaseToken,
            long fencingToken) {
        lockRun(agentRunId);
        return jdbcTemplate.update("""
                UPDATE platform_run_worker_leases
                SET lease_until = clock_timestamp(), released_at = clock_timestamp(),
                    revision = revision + 1
                WHERE agent_run_id = ?
                  AND lease_owner = ?
                  AND lease_token = CAST(? AS UUID)
                  AND fencing_token = ?
                  AND released_at IS NULL
                  AND lease_until > clock_timestamp()
                """, agentRunId, leaseOwner, leaseToken, fencingToken) == 1;
    }

    @Override
    public boolean workerLeaseIsActive(
            String agentRunId,
            String leaseToken,
            long fencingToken) {
        Boolean active = jdbcTemplate.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM platform_run_worker_leases
                    WHERE agent_run_id = ?
                      AND lease_token = CAST(? AS UUID)
                      AND fencing_token = ?
                      AND released_at IS NULL
                      AND lease_until > clock_timestamp())
                """, Boolean.class, agentRunId, leaseToken, fencingToken);
        return Boolean.TRUE.equals(active);
    }

    @Override
    public RuntimeContinuation saveContinuationIfAbsent(RuntimeContinuation continuation) {
        lockNonTerminalRun(continuation.agentRunId());
        jdbcTemplate.update("""
                INSERT INTO platform_runtime_continuations(
                    id, agent_run_id, continuation_type, deduplication_key, payload,
                    state, available_at, attempt, max_attempts, claim_token, claim_owner,
                    fencing_token, lease_until, revision, last_error,
                    created_at, updated_at, completed_at)
                VALUES (CAST(? AS UUID), ?, ?, ?, CAST(? AS JSONB), ?, ?, ?, ?,
                        CAST(? AS UUID), ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(agent_run_id, deduplication_key) DO NOTHING
                """,
                continuation.id(), continuation.agentRunId(), continuation.type().name(),
                continuation.deduplicationKey(), continuation.payload(),
                continuation.state().name(), timestamp(continuation.availableAt()),
                continuation.attempt(), continuation.maxAttempts(), continuation.claimToken(),
                continuation.claimOwner(), continuation.fencingToken(),
                timestamp(continuation.leaseUntil()), continuation.revision(),
                continuation.lastError(), timestamp(continuation.createdAt()),
                timestamp(continuation.updatedAt()), timestamp(continuation.completedAt()));
        return findContinuationByDeduplicationKey(
                continuation.agentRunId(), continuation.deduplicationKey()).orElseThrow();
    }

    @Override
    public Optional<RuntimeContinuation> findContinuationById(String continuationId) {
        return jdbcTemplate.query(continuationSelect() + " WHERE id = CAST(? AS UUID)",
                this::mapContinuation, continuationId).stream().findFirst();
    }

    @Override
    public List<RuntimeContinuation> findContinuationsByRunId(String agentRunId) {
        return jdbcTemplate.query(continuationSelect()
                        + " WHERE agent_run_id = ? ORDER BY created_at, id",
                this::mapContinuation, agentRunId);
    }

    @Override
    public Optional<RuntimeContinuationClaim> claimNextContinuation(
            String leaseOwner,
            String leaseToken,
            int leaseSeconds) {
        jdbcTemplate.update("""
                UPDATE platform_runtime_continuations
                SET state = 'FAILED', claim_token = NULL, claim_owner = NULL,
                    fencing_token = NULL, lease_until = NULL, revision = revision + 1,
                    last_error = 'Continuation lease expired after maximum attempts',
                    updated_at = clock_timestamp(), completed_at = clock_timestamp()
                WHERE state = 'CLAIMED' AND lease_until <= clock_timestamp()
                  AND attempt >= max_attempts
                """);
        List<RuntimeContinuation> candidates = jdbcTemplate.query("""
                SELECT continuation.id, continuation.agent_run_id,
                       continuation.continuation_type, continuation.deduplication_key,
                       continuation.payload::text AS payload, continuation.state,
                       continuation.available_at, continuation.attempt,
                       continuation.max_attempts, continuation.claim_token,
                       continuation.claim_owner, continuation.fencing_token,
                       continuation.lease_until, continuation.revision,
                       continuation.last_error, continuation.created_at,
                       continuation.updated_at, continuation.completed_at
                FROM platform_runtime_continuations continuation
                JOIN platform_agent_runs run ON run.id = continuation.agent_run_id
                WHERE ((continuation.state = 'PENDING'
                            AND continuation.available_at <= clock_timestamp())
                       OR (continuation.state = 'CLAIMED'
                            AND continuation.lease_until <= clock_timestamp()))
                  AND continuation.attempt < continuation.max_attempts
                  AND run.state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED')
                ORDER BY continuation.available_at, continuation.created_at, continuation.id
                LIMIT 16
                FOR UPDATE OF run SKIP LOCKED
                """, this::mapContinuation);
        for (RuntimeContinuation candidate : candidates) {
            RunWorkerLeaseClaim lease = claimWorkerLease(
                    candidate.agentRunId(), leaseOwner, leaseToken, leaseSeconds);
            if (lease.type() != RunWorkerLeaseClaimType.ACQUIRED) {
                continue;
            }
            List<RuntimeContinuation> claimed = jdbcTemplate.query("""
                    UPDATE platform_runtime_continuations
                    SET state = 'CLAIMED', attempt = attempt + 1,
                        claim_token = CAST(? AS UUID), claim_owner = ?, fencing_token = ?,
                        lease_until = ?, revision = revision + 1,
                        updated_at = clock_timestamp(), completed_at = NULL
                    WHERE id = CAST(? AS UUID)
                      AND (state = 'PENDING'
                           OR (state = 'CLAIMED' AND lease_until <= clock_timestamp()))
                      AND revision = ?
                    RETURNING id, agent_run_id, continuation_type, deduplication_key,
                              payload::text AS payload, state, available_at, attempt,
                              max_attempts, claim_token, claim_owner, fencing_token,
                              lease_until, revision, last_error, created_at, updated_at,
                              completed_at
                    """, this::mapContinuation, leaseToken, leaseOwner,
                    lease.lease().fencingToken(), timestamp(lease.lease().leaseUntil()),
                    candidate.id(), candidate.revision());
            if (!claimed.isEmpty()) {
                return Optional.of(new RuntimeContinuationClaim(
                        claimed.getFirst(), lease.lease()));
            }
            releaseWorkerLease(candidate.agentRunId(), leaseOwner, leaseToken,
                    lease.lease().fencingToken());
            return Optional.empty();
        }
        return Optional.empty();
    }

    @Override
    public Optional<RuntimeContinuationClaim> heartbeatContinuation(
            String continuationId,
            String leaseOwner,
            String claimToken,
            long fencingToken,
            int leaseSeconds) {
        RuntimeContinuation current = findContinuationById(continuationId).orElse(null);
        if (!claimedBy(current, leaseOwner, claimToken, fencingToken)) {
            return Optional.empty();
        }
        Optional<RunWorkerLease> lease = heartbeatWorkerLease(
                current.agentRunId(), leaseOwner, claimToken, fencingToken, leaseSeconds);
        if (lease.isEmpty()) {
            return Optional.empty();
        }
        List<RuntimeContinuation> updated = jdbcTemplate.query("""
                UPDATE platform_runtime_continuations
                SET lease_until = ?, revision = revision + 1,
                    updated_at = clock_timestamp()
                WHERE id = CAST(? AS UUID) AND state = 'CLAIMED'
                  AND claim_owner = ? AND claim_token = CAST(? AS UUID)
                  AND fencing_token = ? AND revision = ?
                RETURNING id, agent_run_id, continuation_type, deduplication_key,
                          payload::text AS payload, state, available_at, attempt,
                          max_attempts, claim_token, claim_owner, fencing_token,
                          lease_until, revision, last_error, created_at, updated_at,
                          completed_at
                """, this::mapContinuation, timestamp(lease.get().leaseUntil()),
                continuationId, leaseOwner, claimToken, fencingToken, current.revision());
        if (updated.isEmpty()) {
            throw new IllegalStateException("Continuation heartbeat lost its fenced claim");
        }
        return Optional.of(new RuntimeContinuationClaim(updated.getFirst(), lease.get()));
    }

    @Override
    public Optional<RuntimeContinuation> completeContinuation(
            String continuationId,
            String leaseOwner,
            String claimToken,
            long fencingToken) {
        RuntimeContinuation current = findContinuationById(continuationId).orElse(null);
        if (current == null) {
            return Optional.empty();
        }
        lockRun(current.agentRunId());
        List<RuntimeContinuation> completed = jdbcTemplate.query("""
                UPDATE platform_runtime_continuations continuation
                SET state = 'COMPLETED', claim_token = NULL, claim_owner = NULL,
                    fencing_token = NULL, lease_until = NULL, revision = revision + 1,
                    updated_at = clock_timestamp(), completed_at = clock_timestamp()
                WHERE continuation.id = CAST(? AS UUID)
                  AND continuation.state = 'CLAIMED'
                  AND continuation.claim_owner = ?
                  AND continuation.claim_token = CAST(? AS UUID)
                  AND continuation.fencing_token = ?
                  AND EXISTS (
                      SELECT 1 FROM platform_run_worker_leases lease
                      WHERE lease.agent_run_id = continuation.agent_run_id
                        AND lease.lease_owner = ?
                        AND lease.lease_token = continuation.claim_token
                        AND lease.fencing_token = continuation.fencing_token
                        AND lease.released_at IS NULL
                        AND lease.lease_until > clock_timestamp())
                RETURNING id, agent_run_id, continuation_type, deduplication_key,
                          payload::text AS payload, state, available_at, attempt,
                          max_attempts, claim_token, claim_owner, fencing_token,
                          lease_until, revision, last_error, created_at, updated_at,
                          completed_at
                """, this::mapContinuation, continuationId, leaseOwner, claimToken,
                fencingToken, leaseOwner);
        if (completed.isEmpty()) {
            return Optional.empty();
        }
        releaseWorkerLease(completed.getFirst().agentRunId(), leaseOwner, claimToken, fencingToken);
        return Optional.of(completed.getFirst());
    }

    @Override
    public Optional<RuntimeContinuation> failContinuation(
            String continuationId,
            String leaseOwner,
            String claimToken,
            long fencingToken,
            String error,
            int retryDelaySeconds) {
        RuntimeContinuation current = findContinuationById(continuationId).orElse(null);
        if (!claimedBy(current, leaseOwner, claimToken, fencingToken)
                || !workerLeaseIsActive(current.agentRunId(), claimToken, fencingToken)) {
            return Optional.empty();
        }
        lockRun(current.agentRunId());
        boolean retry = current.attempt() < current.maxAttempts();
        RuntimeContinuationState state = retry
                ? RuntimeContinuationState.PENDING
                : RuntimeContinuationState.FAILED;
        List<RuntimeContinuation> failed = jdbcTemplate.query("""
                UPDATE platform_runtime_continuations
                SET state = ?,
                    available_at = CASE WHEN ? THEN
                        clock_timestamp() + (? * INTERVAL '1 second') ELSE available_at END,
                    claim_token = NULL, claim_owner = NULL, fencing_token = NULL,
                    lease_until = NULL, revision = revision + 1, last_error = ?,
                    updated_at = clock_timestamp(),
                    completed_at = CASE WHEN ? THEN NULL ELSE clock_timestamp() END
                WHERE id = CAST(? AS UUID) AND state = 'CLAIMED'
                  AND claim_owner = ? AND claim_token = CAST(? AS UUID)
                  AND fencing_token = ? AND revision = ?
                RETURNING id, agent_run_id, continuation_type, deduplication_key,
                          payload::text AS payload, state, available_at, attempt,
                          max_attempts, claim_token, claim_owner, fencing_token,
                          lease_until, revision, last_error, created_at, updated_at,
                          completed_at
                """, this::mapContinuation, state.name(), retry, retryDelaySeconds,
                error, retry, continuationId, leaseOwner, claimToken, fencingToken,
                current.revision());
        if (failed.isEmpty()) {
            return Optional.empty();
        }
        releaseWorkerLease(current.agentRunId(), leaseOwner, claimToken, fencingToken);
        return Optional.of(failed.getFirst());
    }

    @Override
    public int cancelPendingContinuations(String agentRunId, String reason) {
        jdbcTemplate.update("""
                UPDATE platform_run_worker_leases
                SET lease_until = clock_timestamp(), released_at = clock_timestamp(),
                    revision = revision + 1
                WHERE agent_run_id = ? AND released_at IS NULL
                """, agentRunId);
        return jdbcTemplate.update("""
                UPDATE platform_runtime_continuations
                SET state = 'CANCELLED', claim_token = NULL, claim_owner = NULL,
                    fencing_token = NULL, lease_until = NULL, revision = revision + 1,
                    last_error = ?, updated_at = clock_timestamp(),
                    completed_at = clock_timestamp()
                WHERE agent_run_id = ? AND state IN ('PENDING', 'CLAIMED')
                """, reason, agentRunId);
    }

    private Optional<RuntimeContinuation> findContinuationByDeduplicationKey(
            String agentRunId,
            String deduplicationKey) {
        return jdbcTemplate.query(continuationSelect()
                        + " WHERE agent_run_id = ? AND deduplication_key = ?",
                this::mapContinuation, agentRunId, deduplicationKey).stream().findFirst();
    }

    private void lockRun(String agentRunId) {
        jdbcTemplate.queryForObject(
                "SELECT id FROM platform_agent_runs WHERE id = ? FOR UPDATE",
                String.class,
                agentRunId);
    }

    private void lockNonTerminalRun(String agentRunId) {
        String state = jdbcTemplate.queryForObject(
                "SELECT state FROM platform_agent_runs WHERE id = ? FOR UPDATE",
                String.class,
                agentRunId);
        if (state == null || List.of("COMPLETED", "FAILED", "CANCELLED").contains(state)) {
            throw new IllegalStateException("AgentRun is terminal or missing: " + agentRunId);
        }
    }

    private static String continuationSelect() {
        return """
                SELECT id, agent_run_id, continuation_type, deduplication_key,
                       payload::text AS payload, state, available_at, attempt,
                       max_attempts, claim_token, claim_owner, fencing_token,
                       lease_until, revision, last_error, created_at, updated_at,
                       completed_at
                FROM platform_runtime_continuations
                """;
    }

    private RunWorkerLease mapLease(ResultSet rs, int rowNum) throws SQLException {
        return new RunWorkerLease(
                rs.getString("agent_run_id"),
                rs.getString("lease_token"),
                rs.getString("lease_owner"),
                rs.getLong("fencing_token"),
                instant(rs.getTimestamp("lease_until")),
                rs.getLong("revision"),
                instant(rs.getTimestamp("acquired_at")),
                instant(rs.getTimestamp("heartbeat_at")),
                instant(rs.getTimestamp("released_at")));
    }

    private RuntimeContinuation mapContinuation(ResultSet rs, int rowNum) throws SQLException {
        long fence = rs.getLong("fencing_token");
        return new RuntimeContinuation(
                rs.getString("id"),
                rs.getString("agent_run_id"),
                RuntimeContinuationType.valueOf(rs.getString("continuation_type")),
                rs.getString("deduplication_key"),
                rs.getString("payload"),
                RuntimeContinuationState.valueOf(rs.getString("state")),
                instant(rs.getTimestamp("available_at")),
                rs.getInt("attempt"),
                rs.getInt("max_attempts"),
                rs.getString("claim_token"),
                rs.getString("claim_owner"),
                rs.wasNull() ? null : fence,
                instant(rs.getTimestamp("lease_until")),
                rs.getLong("revision"),
                rs.getString("last_error"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                instant(rs.getTimestamp("completed_at")));
    }

    private static boolean claimedBy(
            RuntimeContinuation continuation,
            String owner,
            String token,
            long fence) {
        return continuation != null
                && continuation.state() == RuntimeContinuationState.CLAIMED
                && owner.equals(continuation.claimOwner())
                && token.equals(continuation.claimToken())
                && continuation.fencingToken() != null
                && continuation.fencingToken() == fence;
    }

    private static void requireNextRevision(AgentRun expected, AgentRun updated) {
        if (!expected.id().equals(updated.id()) || updated.revision() != expected.revision() + 1) {
            throw new IllegalArgumentException("AgentRun CAS requires exactly one revision advance");
        }
    }

    private AgentRun mapRun(ResultSet rs, int rowNum) throws SQLException {
        return new AgentRun(
                rs.getString("id"),
                rs.getString("agent_id"),
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("owner_id"),
                rs.getString("conversation_id"),
                rs.getString("chat_task_id"),
                rs.getString("project_id"),
                rs.getString("project_directory_id"),
                rs.getString("workspace_id"),
                rs.getString("task_id"),
                rs.getString("task_plan_id"),
                rs.getString("plan_step_id"),
                readCursor(rs.getString("execution_cursor")),
                rs.getLong("revision"),
                AgentRunState.valueOf(rs.getString("state")),
                rs.getString("failure_reason"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")),
                instant(rs.getTimestamp("completed_at")));
    }

    private RunEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        return new RunEvent(
                rs.getString("id"), rs.getString("agent_run_id"),
                rs.getLong("sequence_number"),
                RunEventType.valueOf(rs.getString("event_type")),
                rs.getString("payload"), readCursor(rs.getString("execution_cursor")),
                instant(rs.getTimestamp("created_at")));
    }

    private Handoff mapHandoff(ResultSet rs, int rowNum) throws SQLException {
        return new Handoff(
                rs.getString("id"),
                rs.getString("source_agent_run_id"),
                rs.getString("target_agent_run_id"),
                HandoffState.valueOf(rs.getString("state")),
                new HandoffSnapshot(
                        rs.getString("goal"),
                        rs.getString("current_state"),
                        readList(rs.getString("completed_work")),
                        readList(rs.getString("decisions")),
                        readList(rs.getString("failed_attempts")),
                        readList(rs.getString("changed_files")),
                        HandoffTestStatus.valueOf(rs.getString("test_status")),
                        readList(rs.getString("blockers")),
                        readList(rs.getString("next_actions"))),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("completed_at")));
    }

    private List<String> readList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, STRING_LIST);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to deserialize handoff snapshot list", error);
        }
    }

    private RunStep mapStep(ResultSet rs, int rowNum) throws SQLException {
        return new RunStep(
                rs.getString("id"),
                rs.getString("agent_run_id"),
                rs.getInt("sequence"),
                rs.getString("type"),
                RunStepState.valueOf(rs.getString("state")),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("completed_at")));
    }

    private Checkpoint mapCheckpoint(ResultSet rs, int rowNum) throws SQLException {
        return new Checkpoint(
                rs.getString("id"),
                rs.getString("agent_run_id"),
                rs.getInt("sequence"),
                rs.getString("state_snapshot"),
                instant(rs.getTimestamp("created_at")));
    }

    private Recovery mapRecovery(ResultSet rs, int rowNum) throws SQLException {
        return new Recovery(
                rs.getString("id"),
                rs.getString("agent_run_id"),
                rs.getInt("attempt"),
                RecoveryState.valueOf(rs.getString("state")),
                rs.getString("reason"),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("completed_at")));
    }

    private String writeList(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize handoff snapshot list", error);
        }
    }

    private String writeCursor(ExecutionCursor cursor) {
        try {
            return objectMapper.writeValueAsString(cursor);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to serialize execution cursor", error);
        }
    }

    private ExecutionCursor readCursor(String value) {
        if (value == null || value.isBlank()) {
            return ExecutionCursor.initial();
        }
        try {
            return objectMapper.readValue(value, ExecutionCursor.class);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to deserialize execution cursor", error);
        }
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Object uuid(String value) {
        return value == null ? null : java.util.UUID.fromString(value);
    }

    private static Object canonicalUuid(AgentRun run, String value) {
        return run.taskScoped() ? uuid(value) : null;
    }
}
