package com.spaceagent.platform.runtime.infrastructure.persistence;

import com.spaceagent.platform.runtime.domain.ProjectRunHandoff;
import com.spaceagent.platform.runtime.domain.ProjectRunHandoffRepository;
import com.spaceagent.platform.runtime.domain.ProjectRunHandoffState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresProjectRunHandoffRepository implements ProjectRunHandoffRepository {
    private static final String SELECT = "SELECT * FROM platform_project_run_handoffs";
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    public PostgresProjectRunHandoffRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    @Override
    public void insert(ProjectRunHandoff value) {
        named.update("""
                INSERT INTO platform_project_run_handoffs(
                  id,tenant_id,owner_id,project_id,project_directory_id,source_repository_id,
                  root_task_id,task_id,task_plan_id,plan_step_id,base_ref,source_coding_job_id,
                  source_agent_run_id,workspace_id,recovery_snapshot_id,recovery_snapshot_hash,
                  target_conversation_id,target_agent_id,reviewer_agent_id,
                  target_coding_job_id,target_agent_run_id,
                  idempotency_hash,input_hash,state,memory_key,safe_error_code,attempt,
                  claim_owner,claim_token,fencing_token,lease_until,revision,created_at,
                  updated_at,completed_at)
                VALUES(CAST(:id AS UUID),:tenant,:owner,CAST(:project AS UUID),
                  CAST(:directory AS UUID),CAST(:source AS UUID),CAST(:rootTask AS UUID),
                  CAST(:task AS UUID),CAST(:plan AS UUID),CAST(:step AS UUID),:baseRef,
                  CAST(:sourceJob AS UUID),:sourceRun,
                  CAST(:workspace AS UUID),CAST(:snapshot AS UUID),:snapshotHash,
                  :targetConversation,:targetAgent,:reviewerAgent,
                  CAST(:targetJob AS UUID),:targetRun,
                  :idempotency,:inputHash,:state,:memoryKey,:error,:attempt,:claimOwner,
                  CAST(:claimToken AS UUID),:fence,:lease,:revision,:created,:updated,:completed)
                """, params(value));
    }

    @Override
    public Optional<ProjectRunHandoff> findById(String id) {
        return jdbc.query(SELECT + " WHERE id=CAST(? AS UUID)", this::map, id).stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<ProjectRunHandoff> findByIdForUpdate(String id) {
        return jdbc.query(SELECT + " WHERE id=CAST(? AS UUID) FOR UPDATE", this::map, id)
                .stream().findFirst();
    }

    @Override
    public Optional<ProjectRunHandoff> findByIdempotency(
            String tenantId, String ownerId, String hash) {
        return jdbc.query(SELECT + " WHERE tenant_id=? AND owner_id=? AND idempotency_hash=?",
                this::map, tenantId, ownerId, hash).stream().findFirst();
    }

    @Override
    public Optional<ProjectRunHandoff> findByTargetCodingJobId(String codingJobId) {
        return jdbc.query(SELECT + " WHERE target_coding_job_id=CAST(? AS UUID)",
                this::map, codingJobId).stream().findFirst();
    }

    @Override
    public Optional<ProjectRunHandoff> findBySourceCodingJobId(String codingJobId) {
        return jdbc.query(SELECT + " WHERE source_coding_job_id=CAST(? AS UUID)",
                this::map, codingJobId).stream().findFirst();
    }

    @Override
    public List<ProjectRunHandoff> findByProject(
            String projectId, String ownerId, int offset, int limit) {
        return jdbc.query(SELECT + " WHERE project_id=CAST(? AS UUID) AND owner_id=? "
                + "ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                this::map, projectId, ownerId, limit, offset);
    }

    @Override
    public long countByProject(String projectId, String ownerId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM platform_project_run_handoffs "
                + "WHERE project_id=CAST(? AS UUID) AND owner_id=?",
                Long.class, projectId, ownerId);
        return count == null ? 0 : count;
    }

    @Override
    public void saveLifecycle(ProjectRunHandoff expected, ProjectRunHandoff updated) {
        MapSqlParameterSource parameters = params(updated)
                .addValue("expectedRevision", expected.revision());
        if (named.update(updateSql() + " WHERE id=CAST(:id AS UUID) "
                + "AND revision=:expectedRevision", parameters) != 1) {
            throw new IllegalStateException("Project Run Handoff revision conflict");
        }
    }

    @Override
    public int promoteCompletedTargets(Instant now) {
        return jdbc.update("""
                UPDATE platform_project_run_handoffs handoff
                   SET state='READY_TO_FINALIZE',revision=handoff.revision+1,updated_at=?
                  FROM platform_project_coding_jobs job
                 WHERE handoff.target_coding_job_id=job.id
                   AND handoff.state IN('PENDING','ACTIVE')
                   AND handoff.target_agent_run_id IS NOT NULL
                   AND job.state='COMPLETED'
                """, ts(now));
    }

    @Override
    @Transactional
    public Optional<ProjectRunHandoff> claimFinalization(
            String workerId, String claimToken, Instant now, Instant leaseUntil,
            int maximumAttempts) {
        jdbc.update("""
                UPDATE platform_project_run_handoffs SET state='BLOCKED',
                  safe_error_code='PROJECT_HANDOFF_FINALIZATION_ATTEMPTS_EXHAUSTED',
                  claim_owner=NULL,claim_token=NULL,lease_until=NULL,revision=revision+1,
                  updated_at=?,completed_at=?
                WHERE state='FINALIZING' AND lease_until<=? AND attempt>=?
                """, ts(now), ts(now), ts(now), maximumAttempts);
        List<String> ids = jdbc.query("""
                WITH candidate AS (
                  SELECT id FROM platform_project_run_handoffs
                  WHERE (state='READY_TO_FINALIZE' OR (state='FINALIZING' AND lease_until<=?))
                    AND attempt<? ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1)
                UPDATE platform_project_run_handoffs handoff SET state='FINALIZING',
                  claim_owner=?,claim_token=CAST(? AS UUID),lease_until=?,attempt=attempt+1,
                  fencing_token=fencing_token+1,revision=revision+1,updated_at=?
                FROM candidate WHERE handoff.id=candidate.id RETURNING handoff.id::text
                """, (row, number) -> row.getString(1), ts(now), maximumAttempts,
                workerId, claimToken, ts(leaseUntil), ts(now));
        return ids.isEmpty() ? Optional.empty() : findById(ids.getFirst());
    }

    @Override
    public boolean saveClaimed(
            ProjectRunHandoff expected, ProjectRunHandoff updated, Instant now) {
        MapSqlParameterSource parameters = params(updated)
                .addValue("expectedRevision", expected.revision())
                .addValue("expectedToken", expected.claimToken())
                .addValue("now", ts(now));
        return named.update(updateSql() + " WHERE id=CAST(:id AS UUID) "
                + "AND revision=:expectedRevision AND state='FINALIZING' "
                + "AND claim_token=CAST(:expectedToken AS UUID) AND fencing_token=:fence "
                + "AND lease_until>:now", parameters) == 1;
    }

    private static String updateSql() {
        return """
                UPDATE platform_project_run_handoffs SET target_agent_run_id=:targetRun,
                  state=:state,memory_key=:memoryKey,safe_error_code=:error,attempt=:attempt,
                  claim_owner=:claimOwner,claim_token=CAST(:claimToken AS UUID),
                  fencing_token=:fence,lease_until=:lease,revision=:revision,
                  updated_at=:updated,completed_at=:completed
                """;
    }

    private static MapSqlParameterSource params(ProjectRunHandoff value) {
        return new MapSqlParameterSource()
                .addValue("id", value.id()).addValue("tenant", value.tenantId())
                .addValue("owner", value.ownerId()).addValue("project", value.projectId())
                .addValue("directory", value.projectDirectoryId())
                .addValue("source", value.sourceRepositoryId())
                .addValue("rootTask", value.rootTaskId()).addValue("task", value.taskId())
                .addValue("plan", value.taskPlanId()).addValue("step", value.planStepId())
                .addValue("baseRef", value.baseRef())
                .addValue("sourceJob", value.sourceCodingJobId())
                .addValue("sourceRun", value.sourceAgentRunId())
                .addValue("workspace", value.workspaceId())
                .addValue("snapshot", value.recoverySnapshotId())
                .addValue("snapshotHash", value.recoverySnapshotHash())
                .addValue("targetConversation", value.targetConversationId())
                .addValue("targetAgent", value.targetAgentId())
                .addValue("reviewerAgent", value.reviewerAgentId())
                .addValue("targetJob", value.targetCodingJobId())
                .addValue("targetRun", value.targetAgentRunId())
                .addValue("idempotency", value.idempotencyHash())
                .addValue("inputHash", value.inputHash()).addValue("state", value.state().name())
                .addValue("memoryKey", value.memoryKey()).addValue("error", value.safeErrorCode())
                .addValue("attempt", value.attempt()).addValue("claimOwner", value.claimOwner())
                .addValue("claimToken", value.claimToken()).addValue("fence", value.fencingToken())
                .addValue("lease", ts(value.leaseUntil())).addValue("revision", value.revision())
                .addValue("created", ts(value.createdAt())).addValue("updated", ts(value.updatedAt()))
                .addValue("completed", ts(value.completedAt()));
    }

    private ProjectRunHandoff map(ResultSet row, int number) throws SQLException {
        return new ProjectRunHandoff(
                row.getString("id"), row.getString("tenant_id"), row.getString("owner_id"),
                row.getString("project_id"), row.getString("project_directory_id"),
                row.getString("source_repository_id"), row.getString("root_task_id"),
                row.getString("task_id"), row.getString("task_plan_id"),
                row.getString("plan_step_id"), row.getString("base_ref"),
                row.getString("source_coding_job_id"), row.getString("source_agent_run_id"),
                row.getString("workspace_id"), row.getString("recovery_snapshot_id"),
                row.getString("recovery_snapshot_hash"), row.getString("target_conversation_id"),
                row.getString("target_agent_id"), row.getString("reviewer_agent_id"),
                row.getString("target_coding_job_id"),
                row.getString("target_agent_run_id"), row.getString("idempotency_hash"),
                row.getString("input_hash"), ProjectRunHandoffState.valueOf(row.getString("state")),
                row.getString("memory_key"), row.getString("safe_error_code"), row.getInt("attempt"),
                row.getString("claim_owner"), row.getString("claim_token"),
                row.getLong("fencing_token"), instant(row.getTimestamp("lease_until")),
                row.getLong("revision"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant(), instant(row.getTimestamp("completed_at")));
    }

    private static Timestamp ts(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
