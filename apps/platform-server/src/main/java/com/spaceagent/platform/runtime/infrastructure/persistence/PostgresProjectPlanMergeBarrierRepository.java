package com.spaceagent.platform.runtime.infrastructure.persistence;

import com.spaceagent.platform.runtime.domain.ProjectPlanMergeBarrier;
import com.spaceagent.platform.runtime.domain.ProjectPlanMergeBarrierClaim;
import com.spaceagent.platform.runtime.domain.ProjectPlanMergeBarrierEntry;
import com.spaceagent.platform.runtime.domain.ProjectPlanMergeBarrierRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresProjectPlanMergeBarrierRepository implements ProjectPlanMergeBarrierRepository {
    private static final String CLAIM_COLUMNS = """
            barrier.execution_id AS barrier_execution_id, barrier.tenant_id, barrier.owner_id,
            barrier.project_id, barrier.task_plan_id, barrier.next_apply_index,
            barrier.state AS barrier_state, barrier.revision AS barrier_revision,
            barrier.created_at AS barrier_created_at, barrier.updated_at AS barrier_updated_at,
            barrier.completed_at AS barrier_completed_at,
            entry.execution_id, entry.apply_index, entry.plan_step_id, entry.source_merge_id,
            entry.state, entry.revision, entry.created_at, entry.updated_at, entry.completed_at,
            entry.attempt, entry.claim_owner, entry.claim_token, entry.fencing_token, entry.lease_until
            """;
    private final JdbcTemplate jdbc;

    public PostgresProjectPlanMergeBarrierRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    @Transactional
    public void create(ProjectPlanMergeBarrier barrier, List<ProjectPlanMergeBarrierEntry> entries) {
        insertBarrier(barrier, false);
        entries.forEach(this::insertEntry);
    }

    @Override
    public void createIfAbsent(ProjectPlanMergeBarrier barrier) { insertBarrier(barrier, true); }

    @Override
    @Transactional
    public boolean append(ProjectPlanMergeBarrierEntry entry) {
        int inserted = jdbc.update("""
                INSERT INTO platform_project_plan_merge_barrier_entries(
                  execution_id,apply_index,plan_step_id,source_merge_id,state,revision,
                  created_at,updated_at,completed_at)
                VALUES(CAST(? AS UUID),?,CAST(? AS UUID),CAST(? AS UUID),'READY',1,?,?,NULL)
                ON CONFLICT DO NOTHING
                """, entry.executionId(), entry.applyIndex(), entry.planStepId(), entry.sourceMergeId(),
                ts(entry.createdAt()), ts(entry.updatedAt()));
        if (inserted == 0) {
            return Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT count(*)=1 FROM platform_project_plan_merge_barrier_entries
                    WHERE execution_id=CAST(? AS UUID) AND apply_index=?
                      AND plan_step_id=CAST(? AS UUID) AND source_merge_id=CAST(? AS UUID)
                    """, Boolean.class, entry.executionId(), entry.applyIndex(),
                    entry.planStepId(), entry.sourceMergeId()));
        }
        jdbc.update("""
                UPDATE platform_project_plan_merge_barriers
                SET state='ACTIVE',revision=revision+1,updated_at=?,completed_at=NULL
                WHERE execution_id=CAST(? AS UUID) AND state='COMPLETED' AND next_apply_index=?
                """, ts(entry.updatedAt()), entry.executionId(), entry.applyIndex());
        return true;
    }

    @Override
    public Optional<ProjectPlanMergeBarrier> find(String executionId) {
        return jdbc.query("SELECT * FROM platform_project_plan_merge_barriers WHERE execution_id=CAST(? AS UUID)",
                this::barrier, executionId).stream().findFirst();
    }

    @Override
    public Optional<ProjectPlanMergeBarrierEntry> next(String executionId) {
        return jdbc.query("""
                SELECT entry.* FROM platform_project_plan_merge_barriers barrier
                JOIN platform_project_plan_merge_barrier_entries entry
                  ON entry.execution_id=barrier.execution_id
                 AND entry.apply_index=barrier.next_apply_index
                WHERE barrier.execution_id=CAST(? AS UUID)
                  AND barrier.state='ACTIVE' AND entry.state='READY'
                """, this::entry, executionId).stream().findFirst();
    }

    @Override
    @Transactional
    public boolean advance(ProjectPlanMergeBarrier barrier,
                           ProjectPlanMergeBarrierEntry entry, Instant now) {
        if (jdbc.update("""
                UPDATE platform_project_plan_merge_barrier_entries
                SET state='APPLIED',revision=revision+1,updated_at=?,completed_at=?
                WHERE execution_id=CAST(? AS UUID) AND apply_index=?
                  AND state='READY' AND revision=? AND claim_token IS NULL
                """, ts(now), ts(now), entry.executionId(), entry.applyIndex(), entry.revision()) != 1) return false;
        advanceBarrier(barrier.executionId(), entry.applyIndex(), barrier.revision(), now);
        return true;
    }

    @Override
    public boolean block(ProjectPlanMergeBarrier barrier, Instant now) {
        return jdbc.update("""
                UPDATE platform_project_plan_merge_barriers
                SET state='BLOCKED',revision=revision+1,updated_at=?,completed_at=NULL
                WHERE execution_id=CAST(? AS UUID) AND state='ACTIVE' AND revision=?
                """, ts(now), barrier.executionId(), barrier.revision()) == 1;
    }

    @Override
    @Transactional
    public Optional<ProjectPlanMergeBarrierClaim> claimNext(
            String owner, String token, Instant now, Instant leaseUntil, int maximumAttempts) {
        String sql = """
                WITH candidate AS (
                  SELECT entry.execution_id,entry.apply_index
                  FROM platform_project_plan_merge_barriers barrier
                  JOIN platform_project_plan_merge_barrier_entries entry
                    ON entry.execution_id=barrier.execution_id
                   AND entry.apply_index=barrier.next_apply_index
                  WHERE barrier.state='ACTIVE' AND entry.state='READY'
                    AND entry.attempt < ?
                    AND (entry.claim_token IS NULL OR entry.lease_until <= ?)
                  ORDER BY entry.updated_at,entry.execution_id,entry.apply_index
                  FOR UPDATE OF entry SKIP LOCKED LIMIT 1
                ), claimed AS (
                  UPDATE platform_project_plan_merge_barrier_entries entry
                  SET attempt=entry.attempt+1,claim_owner=?,claim_token=CAST(? AS UUID),
                      fencing_token=entry.fencing_token+1,lease_until=?,
                      revision=entry.revision+1,updated_at=?
                  FROM candidate
                  WHERE entry.execution_id=candidate.execution_id
                    AND entry.apply_index=candidate.apply_index
                  RETURNING entry.*
                )
                SELECT %s FROM claimed entry
                JOIN platform_project_plan_merge_barriers barrier
                  ON barrier.execution_id=entry.execution_id
                """.formatted(CLAIM_COLUMNS);
        return jdbc.query(sql, this::claim, maximumAttempts, ts(now), owner, token,
                ts(leaseUntil), ts(now)).stream().findFirst();
    }

    @Override
    public boolean heartbeat(ProjectPlanMergeBarrierClaim claim, Instant now, Instant leaseUntil) {
        return jdbc.update("""
                UPDATE platform_project_plan_merge_barrier_entries
                SET lease_until=?,revision=revision+1,updated_at=?
                WHERE execution_id=CAST(? AS UUID) AND apply_index=? AND state='READY'
                  AND claim_owner=? AND claim_token=CAST(? AS UUID) AND fencing_token=?
                  AND lease_until>?
                """, ts(leaseUntil), ts(now), claim.executionId(), claim.applyIndex(),
                claim.claimOwner(), claim.claimToken(), claim.fencingToken(), ts(now)) == 1;
    }

    @Override
    public boolean releaseKnownNoEffect(ProjectPlanMergeBarrierClaim claim, Instant now) {
        return jdbc.update("""
                UPDATE platform_project_plan_merge_barrier_entries
                SET claim_owner=NULL,claim_token=NULL,lease_until=NULL,
                    revision=revision+1,updated_at=?
                WHERE execution_id=CAST(? AS UUID) AND apply_index=? AND state='READY'
                  AND claim_owner=? AND claim_token=CAST(? AS UUID) AND fencing_token=?
                  AND lease_until>?
                """, ts(now), claim.executionId(), claim.applyIndex(),
                claim.claimOwner(), claim.claimToken(), claim.fencingToken(), ts(now)) == 1;
    }

    @Override
    @Transactional
    public boolean completeApplied(ProjectPlanMergeBarrierClaim claim, Instant now) {
        var locked = lockClaim(claim, now).orElse(null);
        if (locked == null) return false;
        if (jdbc.update("""
                UPDATE platform_project_plan_merge_barrier_entries
                SET state='APPLIED',claim_owner=NULL,claim_token=NULL,lease_until=NULL,
                    safe_error_code=NULL,revision=revision+1,updated_at=?,completed_at=?
                WHERE execution_id=CAST(? AS UUID) AND apply_index=?
                  AND claim_token=CAST(? AS UUID) AND fencing_token=?
                """, ts(now), ts(now), claim.executionId(), claim.applyIndex(),
                claim.claimToken(), claim.fencingToken()) != 1) return false;
        advanceBarrier(locked.executionId(), locked.applyIndex(),
                locked.barrierRevision(), now);
        return true;
    }

    @Override
    @Transactional
    public boolean blockClaim(ProjectPlanMergeBarrierClaim claim,
                              ProjectPlanMergeBarrierEntry.State state,
                              String safeErrorCode, Instant now) {
        if (state != ProjectPlanMergeBarrierEntry.State.BLOCKED
                && state != ProjectPlanMergeBarrierEntry.State.UNKNOWN) {
            throw new IllegalArgumentException("barrier terminal claim state is invalid");
        }
        var locked = lockClaim(claim, now).orElse(null);
        if (locked == null) return false;
        if (jdbc.update("""
                UPDATE platform_project_plan_merge_barrier_entries
                SET state=?,claim_owner=NULL,claim_token=NULL,lease_until=NULL,
                    safe_error_code=?,revision=revision+1,updated_at=?,completed_at=?
                WHERE execution_id=CAST(? AS UUID) AND apply_index=?
                  AND claim_token=CAST(? AS UUID) AND fencing_token=?
                """, state.name(), safeErrorCode, ts(now), ts(now),
                claim.executionId(), claim.applyIndex(),
                claim.claimToken(), claim.fencingToken()) != 1) return false;
        if (jdbc.update("""
                UPDATE platform_project_plan_merge_barriers
                SET state='BLOCKED',revision=revision+1,updated_at=?,completed_at=NULL
                WHERE execution_id=CAST(? AS UUID) AND state='ACTIVE'
                  AND next_apply_index=? AND revision=?
                """, ts(now), locked.executionId(), locked.applyIndex(),
                locked.barrierRevision()) != 1) {
            throw new IllegalStateException("merge barrier changed during terminal claim");
        }
        return true;
    }

    @Override
    @Transactional
    public int blockExhausted(int maximumAttempts, Instant now) {
        var exhausted = jdbc.query("""
                SELECT entry.execution_id,entry.apply_index
                FROM platform_project_plan_merge_barriers barrier
                JOIN platform_project_plan_merge_barrier_entries entry
                  ON entry.execution_id=barrier.execution_id
                 AND entry.apply_index=barrier.next_apply_index
                WHERE barrier.state='ACTIVE' AND entry.state='READY'
                  AND entry.attempt>=?
                  AND (entry.claim_token IS NULL OR entry.lease_until<=?)
                FOR UPDATE OF entry,barrier SKIP LOCKED
                """, (row, number) -> new EntryKey(
                        row.getString("execution_id"), row.getInt("apply_index")),
                maximumAttempts, ts(now));
        for (var key : exhausted) {
            jdbc.update("""
                    UPDATE platform_project_plan_merge_barrier_entries
                    SET state='UNKNOWN',claim_owner=NULL,claim_token=NULL,lease_until=NULL,
                        safe_error_code='PROJECT_MERGE_BARRIER_ATTEMPTS_EXHAUSTED',
                        revision=revision+1,updated_at=?,completed_at=?
                    WHERE execution_id=CAST(? AS UUID) AND apply_index=?
                    """, ts(now), ts(now), key.executionId(), key.applyIndex());
            jdbc.update("""
                    UPDATE platform_project_plan_merge_barriers
                    SET state='BLOCKED',revision=revision+1,updated_at=?,completed_at=NULL
                    WHERE execution_id=CAST(? AS UUID) AND state='ACTIVE' AND next_apply_index=?
                    """, ts(now), key.executionId(), key.applyIndex());
        }
        return exhausted.size();
    }

    private Optional<ProjectPlanMergeBarrierClaim> lockClaim(
            ProjectPlanMergeBarrierClaim claim, Instant now) {
        return jdbc.query("""
                SELECT %s FROM platform_project_plan_merge_barriers barrier
                JOIN platform_project_plan_merge_barrier_entries entry
                  ON entry.execution_id=barrier.execution_id
                 AND entry.apply_index=barrier.next_apply_index
                WHERE entry.execution_id=CAST(? AS UUID) AND entry.apply_index=?
                  AND barrier.state='ACTIVE' AND entry.state='READY'
                  AND entry.claim_owner=? AND entry.claim_token=CAST(? AS UUID)
                  AND entry.fencing_token=? AND entry.lease_until>?
                FOR UPDATE OF entry,barrier
                """.formatted(CLAIM_COLUMNS), this::claim,
                claim.executionId(), claim.applyIndex(), claim.claimOwner(),
                claim.claimToken(), claim.fencingToken(), ts(now)).stream().findFirst();
    }

    private void advanceBarrier(String executionId, int applyIndex, long revision, Instant now) {
        int remaining = jdbc.queryForObject("""
                SELECT count(*) FROM platform_project_plan_merge_barrier_entries
                WHERE execution_id=CAST(? AS UUID) AND apply_index>? AND state='READY'
                """, Integer.class, executionId, applyIndex);
        if (jdbc.update("""
                UPDATE platform_project_plan_merge_barriers
                SET next_apply_index=?,state=?,revision=revision+1,updated_at=?,completed_at=?
                WHERE execution_id=CAST(? AS UUID) AND revision=? AND next_apply_index=?
                """, applyIndex + 1, remaining == 0 ? "COMPLETED" : "ACTIVE", ts(now),
                remaining == 0 ? ts(now) : null, executionId, revision, applyIndex) != 1) {
            throw new IllegalStateException("merge barrier cursor CAS failed");
        }
    }

    private void insertBarrier(ProjectPlanMergeBarrier barrier, boolean ifAbsent) {
        jdbc.update("""
                INSERT INTO platform_project_plan_merge_barriers(
                  execution_id,tenant_id,owner_id,project_id,task_plan_id,next_apply_index,
                  state,revision,created_at,updated_at,completed_at)
                VALUES(CAST(? AS UUID),?,?,CAST(? AS UUID),CAST(? AS UUID),?,?,?,?,?,?)
                %s
                """.formatted(ifAbsent ? "ON CONFLICT(execution_id) DO NOTHING" : ""),
                barrier.executionId(), barrier.tenantId(), barrier.ownerId(), barrier.projectId(),
                barrier.taskPlanId(), barrier.nextApplyIndex(), barrier.state().name(),
                barrier.revision(), ts(barrier.createdAt()), ts(barrier.updatedAt()),
                ts(barrier.completedAt()));
    }

    private void insertEntry(ProjectPlanMergeBarrierEntry entry) {
        jdbc.update("""
                INSERT INTO platform_project_plan_merge_barrier_entries(
                  execution_id,apply_index,plan_step_id,source_merge_id,state,revision,
                  created_at,updated_at,completed_at)
                VALUES(CAST(? AS UUID),?,CAST(? AS UUID),CAST(? AS UUID),?,?,?,?,?)
                """, entry.executionId(), entry.applyIndex(), entry.planStepId(), entry.sourceMergeId(),
                entry.state().name(), entry.revision(), ts(entry.createdAt()),
                ts(entry.updatedAt()), ts(entry.completedAt()));
    }

    private ProjectPlanMergeBarrier barrier(ResultSet row, int number) throws SQLException {
        Timestamp completed = row.getTimestamp("completed_at");
        return new ProjectPlanMergeBarrier(row.getString("execution_id"), row.getString("tenant_id"),
                row.getString("owner_id"), row.getString("project_id"), row.getString("task_plan_id"),
                row.getInt("next_apply_index"), ProjectPlanMergeBarrier.State.valueOf(row.getString("state")),
                row.getLong("revision"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(), completed == null ? null : completed.toInstant());
    }

    private ProjectPlanMergeBarrierEntry entry(ResultSet row, int number) throws SQLException {
        Timestamp completed = row.getTimestamp("completed_at");
        return new ProjectPlanMergeBarrierEntry(row.getString("execution_id"), row.getInt("apply_index"),
                row.getString("plan_step_id"), row.getString("source_merge_id"),
                ProjectPlanMergeBarrierEntry.State.valueOf(row.getString("state")), row.getLong("revision"),
                row.getTimestamp("created_at").toInstant(), row.getTimestamp("updated_at").toInstant(),
                completed == null ? null : completed.toInstant());
    }

    private ProjectPlanMergeBarrierClaim claim(ResultSet row, int number) throws SQLException {
        Timestamp barrierCompleted = row.getTimestamp("barrier_completed_at");
        var barrier = new ProjectPlanMergeBarrier(row.getString("barrier_execution_id"),
                row.getString("tenant_id"), row.getString("owner_id"), row.getString("project_id"),
                row.getString("task_plan_id"), row.getInt("next_apply_index"),
                ProjectPlanMergeBarrier.State.valueOf(row.getString("barrier_state")),
                row.getLong("barrier_revision"), row.getTimestamp("barrier_created_at").toInstant(),
                row.getTimestamp("barrier_updated_at").toInstant(),
                barrierCompleted == null ? null : barrierCompleted.toInstant());
        var entry = entry(row, number);
        return new ProjectPlanMergeBarrierClaim(barrier.executionId(), barrier.tenantId(),
                barrier.ownerId(), barrier.projectId(), barrier.taskPlanId(), entry.applyIndex(),
                entry.planStepId(), entry.sourceMergeId(), barrier.revision(), row.getInt("attempt"),
                row.getString("claim_owner"), row.getString("claim_token"),
                row.getLong("fencing_token"), row.getTimestamp("lease_until").toInstant());
    }

    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
    private record EntryKey(String executionId, int applyIndex) {}
}
