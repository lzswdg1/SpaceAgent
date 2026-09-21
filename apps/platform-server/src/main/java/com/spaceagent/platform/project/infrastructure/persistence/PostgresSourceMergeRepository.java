package com.spaceagent.platform.project.infrastructure.persistence;

import com.spaceagent.platform.project.domain.SourceMerge;
import com.spaceagent.platform.project.domain.SourceMergeRepository;
import com.spaceagent.platform.project.domain.SourceMergeState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresSourceMergeRepository implements SourceMergeRepository {
    private static final String COLUMNS = """
            id,tenant_id,project_id,task_id,source_repository_id,workspace_id,agent_run_id,
            review_id,commit_proposal_artifact_id,target_ref,expected_base_commit,patch_hash,
            commit_message,idempotency_hash,input_hash,state,prepared_commit,actual_target_commit,
            governance_approval_id,failure_code,revision,created_by,created_at,updated_at,
            applied_at,rolled_back_at
            """;

    private final JdbcTemplate jdbc;

    public PostgresSourceMergeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(SourceMerge value) {
        jdbc.update("""
                INSERT INTO platform_source_merge_jobs(
                    id,tenant_id,project_id,task_id,source_repository_id,workspace_id,
                    agent_run_id,review_id,commit_proposal_artifact_id,target_ref,
                    expected_base_commit,patch_hash,commit_message,idempotency_hash,input_hash,
                    state,prepared_commit,actual_target_commit,governance_approval_id,
                    failure_code,revision,created_by,created_at,updated_at,applied_at,rolled_back_at)
                VALUES(CAST(? AS UUID),?,CAST(? AS UUID),CAST(? AS UUID),CAST(? AS UUID),
                    CAST(? AS UUID),?,CAST(? AS UUID),CAST(? AS UUID),?,?,?,?,?,?,?,NULL,NULL,
                    NULL,NULL,?,?,?, ?,NULL,NULL)
                """,
                value.id(), value.tenantId(), value.projectId(), value.taskId(),
                value.sourceRepositoryId(), value.workspaceId(), value.agentRunId(),
                value.reviewId(), value.commitProposalArtifactId(), value.targetRef(),
                value.expectedBaseCommit(), value.patchHash(), value.commitMessage(),
                value.idempotencyHash(), value.inputHash(), value.state().name(),
                value.revision(), value.createdBy(), Timestamp.from(value.createdAt()),
                Timestamp.from(value.updatedAt()));
    }

    @Override
    public Optional<SourceMerge> findById(String id) {
        return jdbc.query("SELECT " + COLUMNS
                + " FROM platform_source_merge_jobs WHERE id=CAST(? AS UUID)",
                this::map, id).stream().findFirst();
    }

    @Override
    public Optional<SourceMerge> findByIdempotency(
            String tenantId, String userId, String idempotencyHash) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_source_merge_jobs"
                        + " WHERE tenant_id=? AND created_by=? AND idempotency_hash=?",
                this::map, tenantId, userId, idempotencyHash).stream().findFirst();
    }

    @Override
    public boolean update(SourceMerge value, long expectedRevision, SourceMergeState expectedState) {
        return jdbc.update("""
                UPDATE platform_source_merge_jobs SET
                    state=?,prepared_commit=?,actual_target_commit=?,governance_approval_id=?,
                    failure_code=?,revision=?,updated_at=?,applied_at=?,rolled_back_at=?
                WHERE id=CAST(? AS UUID) AND revision=? AND state=?
                """,
                value.state().name(), value.preparedCommit(), value.actualTargetCommit(),
                value.governanceApprovalId(), value.failureCode(), value.revision(),
                Timestamp.from(value.updatedAt()), timestamp(value.appliedAt()),
                timestamp(value.rolledBackAt()), value.id(), expectedRevision,
                expectedState.name()) == 1;
    }

    private SourceMerge map(ResultSet result, int row) throws SQLException {
        return new SourceMerge(
                result.getString("id"), result.getString("tenant_id"),
                result.getString("project_id"), result.getString("task_id"),
                result.getString("source_repository_id"), result.getString("workspace_id"),
                result.getString("agent_run_id"), result.getString("review_id"),
                result.getString("commit_proposal_artifact_id"), result.getString("target_ref"),
                result.getString("expected_base_commit"), result.getString("patch_hash"),
                result.getString("commit_message"), result.getString("idempotency_hash"),
                result.getString("input_hash"), SourceMergeState.valueOf(result.getString("state")),
                result.getString("prepared_commit"), result.getString("actual_target_commit"),
                result.getString("governance_approval_id"), result.getString("failure_code"),
                result.getLong("revision"), result.getString("created_by"),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("updated_at").toInstant(),
                instant(result.getTimestamp("applied_at")),
                instant(result.getTimestamp("rolled_back_at")));
    }

    private static Timestamp timestamp(java.time.Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static java.time.Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
