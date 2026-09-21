package com.spaceagent.platform.project.infrastructure.persistence;

import com.spaceagent.platform.project.domain.ProjectIntakeJob;
import com.spaceagent.platform.project.domain.ProjectIntakeRepository;
import com.spaceagent.platform.project.domain.ProjectIntakeState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
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
public class PostgresProjectIntakeRepository implements ProjectIntakeRepository {
    private static final String SELECT = "SELECT * FROM platform_project_intake_jobs";
    private final JdbcTemplate jdbc;

    public PostgresProjectIntakeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(ProjectIntakeJob value) {
        jdbc.update("""
                INSERT INTO platform_project_intake_jobs(
                    id,tenant_id,owner_id,project_id,project_directory_id,source_repository_id,
                    conversation_id,agent_id,goal,idempotency_hash,input_hash,
                    state,attempt,claim_owner,claim_token,fencing_token,lease_until,workspace_ref,
                    source_head_commit,inspection_hash,inspection_json,proposal_hash,proposal_json,
                    agent_run_id,blueprint_id,root_task_id,task_plan_id,safe_error_code,
                    reviewed_by,review_reason,reviewed_at,revision,created_at,started_at,updated_at,
                    completed_at,workspace_cleaned_at)
                VALUES(
                    CAST(? AS UUID),?,?,CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),
                    ?,?,?,?,?,
                    ?,?,?,CAST(? AS UUID),?,?,?,
                    ?,?,?,?,?,
                    ?,CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),?,
                    ?,?,?,?,
                    ?,?,?,?,?)
                """, args(value));
    }

    @Override
    public Optional<ProjectIntakeJob> findById(String id) {
        return jdbc.query(SELECT + " WHERE id=CAST(? AS UUID)", this::map, id)
                .stream().findFirst();
    }

    @Override
    @Transactional
    public Optional<ProjectIntakeJob> findByIdForUpdate(String id) {
        return jdbc.query(SELECT + " WHERE id=CAST(? AS UUID) FOR UPDATE", this::map, id)
                .stream().findFirst();
    }

    @Override
    public Optional<ProjectIntakeJob> findByIdempotency(
            String tenantId, String ownerId, String idempotencyHash) {
        return jdbc.query(SELECT + " WHERE tenant_id=? AND owner_id=? AND idempotency_hash=?",
                this::map, tenantId, ownerId, idempotencyHash).stream().findFirst();
    }

    @Override
    public List<ProjectIntakeJob> findByDirectory(
            String projectDirectoryId, String ownerId, int offset, int limit) {
        return jdbc.query(SELECT + " WHERE project_directory_id=CAST(? AS UUID) AND owner_id=? "
                        + "ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?", this::map,
                projectDirectoryId, ownerId, limit, offset);
    }

    @Override
    public long countByDirectory(String projectDirectoryId, String ownerId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM platform_project_intake_jobs "
                + "WHERE project_directory_id=CAST(? AS UUID) AND owner_id=?", Long.class,
                projectDirectoryId, ownerId);
        return count == null ? 0 : count;
    }

    @Override
    @Transactional
    public Optional<ProjectIntakeJob> claim(
            String workerId, String claimToken, Instant now, Instant leaseUntil,
            int maximumAttempts) {
        jdbc.update("""
                UPDATE platform_project_intake_jobs
                   SET state='FAILED',safe_error_code='PROJECT_INTAKE_ATTEMPTS_EXHAUSTED',
                       claim_owner=NULL,claim_token=NULL,lease_until=NULL,revision=revision+1,
                       updated_at=?,completed_at=?
                 WHERE state='RUNNING' AND lease_until<=? AND attempt>=?
                """, ts(now), ts(now), ts(now), maximumAttempts);
        List<String> ids = jdbc.query("""
                WITH candidate AS (
                    SELECT id FROM platform_project_intake_jobs
                     WHERE (state='PENDING' OR (state='RUNNING' AND lease_until<=?))
                       AND attempt<? ORDER BY created_at,id
                     FOR UPDATE SKIP LOCKED LIMIT 1)
                UPDATE platform_project_intake_jobs job
                   SET state='RUNNING',claim_owner=?,claim_token=CAST(? AS UUID),lease_until=?,
                       attempt=attempt+1,fencing_token=fencing_token+1,revision=revision+1,
                       started_at=COALESCE(started_at,?),updated_at=?
                  FROM candidate WHERE job.id=candidate.id
                RETURNING job.id::TEXT
                """, (rs, row) -> rs.getString(1), ts(now), maximumAttempts, workerId,
                claimToken, ts(leaseUntil), ts(now), ts(now));
        return ids.isEmpty() ? Optional.empty() : findById(ids.getFirst());
    }

    @Override
    public boolean updateClaimed(
            ProjectIntakeJob expected, ProjectIntakeJob updated, Instant now) {
        return jdbc.update("""
                UPDATE platform_project_intake_jobs SET
                    state=?,attempt=?,claim_owner=?,claim_token=CAST(? AS UUID),fencing_token=?,
                    lease_until=?,workspace_ref=?,source_head_commit=?,inspection_hash=?,
                    inspection_json=?,proposal_hash=?,proposal_json=?,agent_run_id=?,blueprint_id=?,
                    root_task_id=?,task_plan_id=?,safe_error_code=?,reviewed_by=?,review_reason=?,
                    reviewed_at=?,revision=?,started_at=?,updated_at=?,completed_at=?,
                    workspace_cleaned_at=?
                 WHERE id=CAST(? AS UUID) AND revision=? AND state='RUNNING'
                   AND claim_owner=? AND claim_token=CAST(? AS UUID) AND fencing_token=?
                   AND lease_until>?
                """, concat(mutable(updated), expected.id(), expected.revision(),
                expected.claimOwner(), expected.claimToken(), expected.fencingToken(), ts(now))) == 1;
    }

    @Override
    public void saveLifecycle(ProjectIntakeJob expected, ProjectIntakeJob updated) {
        int count = jdbc.update("""
                UPDATE platform_project_intake_jobs SET
                    state=?,attempt=?,claim_owner=?,claim_token=CAST(? AS UUID),fencing_token=?,
                    lease_until=?,workspace_ref=?,source_head_commit=?,inspection_hash=?,
                    inspection_json=?,proposal_hash=?,proposal_json=?,agent_run_id=?,blueprint_id=?,
                    root_task_id=?,task_plan_id=?,safe_error_code=?,reviewed_by=?,review_reason=?,
                    reviewed_at=?,revision=?,started_at=?,updated_at=?,completed_at=?,
                    workspace_cleaned_at=?
                 WHERE id=CAST(? AS UUID) AND revision=?
                """, concat(mutable(updated), expected.id(), expected.revision()));
        if (count != 1) throw new IllegalStateException("Project intake revision conflict");
    }

    @Override
    public Optional<ProjectIntakeJob> findWorkspaceCleanupCandidate() {
        return jdbc.query(SELECT + " WHERE workspace_ref IS NOT NULL "
                + "AND workspace_cleaned_at IS NULL AND state NOT IN ('PENDING','RUNNING') "
                + "ORDER BY updated_at,id LIMIT 1", this::map).stream().findFirst();
    }

    @Override
    public boolean markWorkspaceCleaned(
            String jobId, long expectedRevision, Instant cleanedAt) {
        return jdbc.update("UPDATE platform_project_intake_jobs SET workspace_cleaned_at=?,"
                        + "updated_at=?,revision=revision+1 WHERE id=CAST(? AS UUID) AND revision=? "
                        + "AND workspace_cleaned_at IS NULL",
                ts(cleanedAt), ts(cleanedAt), jobId, expectedRevision) == 1;
    }

    private Object[] args(ProjectIntakeJob value) {
        return new Object[]{value.id(),value.tenantId(),value.ownerId(),value.projectId(),
                value.projectDirectoryId(),value.sourceRepositoryId(),value.conversationId(),
                value.agentId(),value.goal(),value.idempotencyHash(),
                value.inputHash(),value.state().name(),value.attempt(),value.claimOwner(),
                value.claimToken(),value.fencingToken(),ts(value.leaseUntil()),value.workspaceRef(),
                value.sourceHeadCommit(),value.inspectionHash(),value.inspectionJson(),
                value.proposalHash(),value.proposalJson(),value.agentRunId(),value.blueprintId(),
                value.rootTaskId(),value.taskPlanId(),value.safeErrorCode(),value.reviewedBy(),
                value.reviewReason(),ts(value.reviewedAt()),value.revision(),ts(value.createdAt()),
                ts(value.startedAt()),ts(value.updatedAt()),ts(value.completedAt()),
                ts(value.workspaceCleanedAt())};
    }

    private Object[] mutable(ProjectIntakeJob value) {
        return new Object[]{value.state().name(),value.attempt(),value.claimOwner(),
                value.claimToken(),value.fencingToken(),ts(value.leaseUntil()),value.workspaceRef(),
                value.sourceHeadCommit(),value.inspectionHash(),value.inspectionJson(),
                value.proposalHash(),value.proposalJson(),value.agentRunId(),value.blueprintId(),
                value.rootTaskId(),value.taskPlanId(),value.safeErrorCode(),value.reviewedBy(),
                value.reviewReason(),ts(value.reviewedAt()),value.revision(),ts(value.startedAt()),
                ts(value.updatedAt()),ts(value.completedAt()),ts(value.workspaceCleanedAt())};
    }

    private ProjectIntakeJob map(ResultSet rs, int row) throws SQLException {
        return new ProjectIntakeJob(rs.getString("id"),rs.getString("tenant_id"),
                rs.getString("owner_id"),rs.getString("project_id"),
                rs.getString("project_directory_id"),rs.getString("source_repository_id"),
                rs.getString("conversation_id"),rs.getString("agent_id"),
                rs.getString("goal"),
                rs.getString("idempotency_hash"),rs.getString("input_hash"),
                ProjectIntakeState.valueOf(rs.getString("state")),rs.getInt("attempt"),
                rs.getString("claim_owner"),rs.getString("claim_token"),
                rs.getLong("fencing_token"),instant(rs.getTimestamp("lease_until")),
                rs.getString("workspace_ref"),rs.getString("source_head_commit"),
                rs.getString("inspection_hash"),rs.getString("inspection_json"),
                rs.getString("proposal_hash"),rs.getString("proposal_json"),
                rs.getString("agent_run_id"),rs.getString("blueprint_id"),
                rs.getString("root_task_id"),rs.getString("task_plan_id"),
                rs.getString("safe_error_code"),rs.getString("reviewed_by"),
                rs.getString("review_reason"),instant(rs.getTimestamp("reviewed_at")),
                rs.getLong("revision"),rs.getTimestamp("created_at").toInstant(),
                instant(rs.getTimestamp("started_at")),rs.getTimestamp("updated_at").toInstant(),
                instant(rs.getTimestamp("completed_at")),
                instant(rs.getTimestamp("workspace_cleaned_at")));
    }

    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    private static Object[] concat(Object[] prefix, Object... suffix) {
        Object[] result = java.util.Arrays.copyOf(prefix, prefix.length + suffix.length);
        System.arraycopy(suffix, 0, result, prefix.length, suffix.length);
        return result;
    }
}
