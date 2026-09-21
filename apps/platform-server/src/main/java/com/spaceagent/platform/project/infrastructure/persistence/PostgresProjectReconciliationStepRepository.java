package com.spaceagent.platform.project.infrastructure.persistence;

import com.spaceagent.platform.project.domain.ProjectReconciliationStep;
import com.spaceagent.platform.project.domain.ProjectReconciliationStepRepository;
import com.spaceagent.platform.project.domain.ProjectReconciliationStepState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PostgresProjectReconciliationStepRepository implements ProjectReconciliationStepRepository {
    private static final String COLUMNS = """
            id,tenant_id,owner_id,project_id,project_directory_id,task_plan_id,plan_step_id,
            execution_id,barrier_id,source_merge_id,expected_base_commit,actual_base_commit,
            original_patch_artifact_id,original_commit_proposal_artifact_id,
            original_test_report_artifact_id,original_review_id,state,proposal_workspace_id,
            proposal_id,proposal_base_commit,resolution_patch_artifact_id,
            resolution_commit_proposal_artifact_id,resolution_test_report_artifact_id,
            resolution_review_id,resolution_source_merge_id,resolution_expected_base_commit,
            resolution_actual_base_commit,blocked_code,revision,created_at,updated_at,resolved_at
            """;

    private final JdbcTemplate jdbc;

    public PostgresProjectReconciliationStepRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(ProjectReconciliationStep value) {
        jdbc.update("""
                INSERT INTO platform_project_reconciliation_steps(
                    id,tenant_id,owner_id,project_id,project_directory_id,task_plan_id,plan_step_id,
                    execution_id,barrier_id,source_merge_id,expected_base_commit,actual_base_commit,
                    original_patch_artifact_id,original_commit_proposal_artifact_id,
                    original_test_report_artifact_id,original_review_id,state,proposal_workspace_id,
                    proposal_id,proposal_base_commit,resolution_patch_artifact_id,
                    resolution_commit_proposal_artifact_id,resolution_test_report_artifact_id,
                    resolution_review_id,resolution_source_merge_id,resolution_expected_base_commit,
                    resolution_actual_base_commit,blocked_code,revision,created_at,updated_at,resolved_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                uuid(value.id()), value.tenantId(), value.ownerUserId(), uuid(value.projectId()),
                uuid(value.projectDirectoryId()), uuid(value.taskPlanId()), uuid(value.planStepId()),
                uuid(value.executionId()), uuid(value.barrierId()), uuid(value.sourceMergeId()),
                value.expectedBaseCommit(), value.actualBaseCommit(), uuid(value.originalPatchArtifactId()),
                uuid(value.originalCommitProposalArtifactId()), uuid(value.originalTestReportArtifactId()),
                uuid(value.originalReviewId()), value.state().name(), uuid(proposalWorkspaceId(value)),
                uuid(proposalId(value)), proposalBaseCommit(value), uuid(resolutionPatchArtifactId(value)),
                uuid(resolutionCommitProposalArtifactId(value)), uuid(resolutionTestReportArtifactId(value)),
                uuid(resolutionReviewId(value)), uuid(resolutionSourceMergeId(value)),
                resolutionExpectedBaseCommit(value), resolutionActualBaseCommit(value), value.blockedCode(),
                value.revision(), Timestamp.from(value.createdAt()), Timestamp.from(value.updatedAt()),
                timestamp(value.resolvedAt()));
    }

    @Override
    public Optional<ProjectReconciliationStep> findById(
            String tenantId, String ownerUserId, String projectId, String reconciliationStepId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_project_reconciliation_steps"
                        + " WHERE id=? AND tenant_id=? AND owner_id=? AND project_id=?",
                this::map, uuid(reconciliationStepId), tenantId, ownerUserId, uuid(projectId))
                .stream().findFirst();
    }

    @Override
    public Optional<ProjectReconciliationStep> findBySourceMergeId(
            String tenantId, String ownerUserId, String projectId, String sourceMergeId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM platform_project_reconciliation_steps"
                        + " WHERE source_merge_id=? AND tenant_id=? AND owner_id=? AND project_id=?",
                this::map, uuid(sourceMergeId), tenantId, ownerUserId, uuid(projectId))
                .stream().findFirst();
    }

    @Override
    public boolean update(
            ProjectReconciliationStep value,
            long expectedRevision,
            ProjectReconciliationStepState expectedState) {
        return jdbc.update("""
                UPDATE platform_project_reconciliation_steps SET
                    state=?,proposal_workspace_id=?,proposal_id=?,proposal_base_commit=?,
                    resolution_patch_artifact_id=?,resolution_commit_proposal_artifact_id=?,
                    resolution_test_report_artifact_id=?,resolution_review_id=?,
                    resolution_source_merge_id=?,resolution_expected_base_commit=?,
                    resolution_actual_base_commit=?,blocked_code=?,revision=?,updated_at=?,resolved_at=?
                WHERE id=? AND tenant_id=? AND owner_id=? AND project_id=? AND revision=? AND state=?
                """,
                value.state().name(), uuid(proposalWorkspaceId(value)), uuid(proposalId(value)),
                proposalBaseCommit(value), uuid(resolutionPatchArtifactId(value)),
                uuid(resolutionCommitProposalArtifactId(value)), uuid(resolutionTestReportArtifactId(value)),
                uuid(resolutionReviewId(value)), uuid(resolutionSourceMergeId(value)),
                resolutionExpectedBaseCommit(value), resolutionActualBaseCommit(value), value.blockedCode(),
                value.revision(), Timestamp.from(value.updatedAt()), timestamp(value.resolvedAt()),
                uuid(value.id()), value.tenantId(), value.ownerUserId(), uuid(value.projectId()),
                expectedRevision, expectedState.name()) == 1;
    }

    private ProjectReconciliationStep map(ResultSet result, int row) throws SQLException {
        String proposalWorkspaceId = result.getString("proposal_workspace_id");
        ProjectReconciliationStep.ReconciliationProposal proposal = proposalWorkspaceId == null ? null
                : new ProjectReconciliationStep.ReconciliationProposal(proposalWorkspaceId,
                result.getString("proposal_id"), result.getString("proposal_base_commit"));
        String resolutionPatchArtifactId = result.getString("resolution_patch_artifact_id");
        ProjectReconciliationStep.ResolutionEvidence resolution = resolutionPatchArtifactId == null ? null
                : new ProjectReconciliationStep.ResolutionEvidence(resolutionPatchArtifactId,
                result.getString("resolution_commit_proposal_artifact_id"),
                result.getString("resolution_test_report_artifact_id"), result.getString("resolution_review_id"),
                result.getString("resolution_source_merge_id"),
                result.getString("resolution_expected_base_commit"),
                result.getString("resolution_actual_base_commit"));
        return new ProjectReconciliationStep(
                result.getString("id"), result.getString("tenant_id"), result.getString("owner_id"),
                result.getString("project_id"), result.getString("project_directory_id"),
                result.getString("task_plan_id"), result.getString("plan_step_id"),
                result.getString("execution_id"), result.getString("barrier_id"),
                result.getString("source_merge_id"), result.getString("expected_base_commit"),
                result.getString("actual_base_commit"), result.getString("original_patch_artifact_id"),
                result.getString("original_commit_proposal_artifact_id"),
                result.getString("original_test_report_artifact_id"), result.getString("original_review_id"),
                ProjectReconciliationStepState.valueOf(result.getString("state")), proposal, resolution,
                result.getString("blocked_code"), result.getLong("revision"),
                result.getTimestamp("created_at").toInstant(), result.getTimestamp("updated_at").toInstant(),
                instant(result.getTimestamp("resolved_at")));
    }

    private static String proposalWorkspaceId(ProjectReconciliationStep value) {
        return value.proposal() == null ? null : value.proposal().workspaceId();
    }

    private static String proposalId(ProjectReconciliationStep value) {
        return value.proposal() == null ? null : value.proposal().proposalId();
    }

    private static String proposalBaseCommit(ProjectReconciliationStep value) {
        return value.proposal() == null ? null : value.proposal().baseCommit();
    }

    private static String resolutionPatchArtifactId(ProjectReconciliationStep value) {
        return value.resolution() == null ? null : value.resolution().patchArtifactId();
    }

    private static String resolutionCommitProposalArtifactId(ProjectReconciliationStep value) {
        return value.resolution() == null ? null : value.resolution().commitProposalArtifactId();
    }

    private static String resolutionTestReportArtifactId(ProjectReconciliationStep value) {
        return value.resolution() == null ? null : value.resolution().testReportArtifactId();
    }

    private static String resolutionReviewId(ProjectReconciliationStep value) {
        return value.resolution() == null ? null : value.resolution().reviewId();
    }

    private static String resolutionSourceMergeId(ProjectReconciliationStep value) {
        return value.resolution() == null ? null : value.resolution().sourceMergeId();
    }

    private static String resolutionExpectedBaseCommit(ProjectReconciliationStep value) {
        return value.resolution() == null ? null : value.resolution().expectedBaseCommit();
    }

    private static String resolutionActualBaseCommit(ProjectReconciliationStep value) {
        return value.resolution() == null ? null : value.resolution().actualBaseCommit();
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
