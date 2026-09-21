package com.spaceagent.platform.project;

import com.spaceagent.platform.project.domain.ProjectReconciliationStep;
import com.spaceagent.platform.project.domain.ProjectReconciliationStepState;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectReconciliationStepDomainTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");
    private static final String EXPECTED_BASE = "a".repeat(40);
    private static final String ACTUAL_BASE = "b".repeat(40);
    private static final String RESOLVED_TARGET = "c".repeat(40);

    @Test
    void baseDriftCreatesIndependentAuditBoundStepWithoutMutatingTheTaskPlan() {
        ProjectReconciliationStep step = baseDrift();

        assertThat(step.state()).isEqualTo(ProjectReconciliationStepState.WAITING_RECONCILIATION);
        assertThat(step.revision()).isEqualTo(1L);
        assertThat(step.taskPlanId()).isEqualTo("plan-1");
        assertThat(step.planStepId()).isEqualTo("step-1");
        assertThat(step.executionId()).isEqualTo("execution-1");
        assertThat(step.barrierId()).isEqualTo("barrier-1");
        assertThat(step.sourceMergeId()).isEqualTo("source-merge-1");
        assertThat(step.expectedBaseCommit()).isEqualTo(EXPECTED_BASE);
        assertThat(step.actualBaseCommit()).isEqualTo(ACTUAL_BASE);
        assertThat(step.originalPatchArtifactId()).isEqualTo("patch-1");
        assertThat(step.originalCommitProposalArtifactId()).isEqualTo("commit-1");
        assertThat(step.originalTestReportArtifactId()).isEqualTo("test-1");
        assertThat(step.originalReviewId()).isEqualTo("review-1");
    }

    @Test
    void isolatedProposalAndCompleteReplacementEvidenceAreRequiredForResolution() {
        ProjectReconciliationStep waiting = baseDrift();
        ProjectReconciliationStep ready = waiting.recordProposal(
                new ProjectReconciliationStep.ReconciliationProposal(
                        "workspace-reconciliation-1", "proposal-1", ACTUAL_BASE), NOW.plusSeconds(1));

        ProjectReconciliationStep resolved = ready.resolve(
                new ProjectReconciliationStep.ResolutionEvidence(
                        "patch-2", "commit-2", "test-2", "review-2", "source-merge-2",
                        ACTUAL_BASE, RESOLVED_TARGET), NOW.plusSeconds(2));

        assertThat(resolved.state()).isEqualTo(ProjectReconciliationStepState.RESOLVED);
        assertThat(resolved.revision()).isEqualTo(3L);
        assertThat(resolved.proposal().workspaceId()).isEqualTo("workspace-reconciliation-1");
        assertThat(resolved.resolution().sourceMergeId()).isEqualTo("source-merge-2");
        assertThat(resolved.resolution().actualBaseCommit()).isEqualTo(RESOLVED_TARGET);
        assertThat(resolved.originalPatchArtifactId()).isEqualTo("patch-1");
    }

    @Test
    void unknownFailsClosedAndInvalidEvidenceCannotProduceResolution() {
        ProjectReconciliationStep waiting = baseDrift();

        assertThatThrownBy(() -> waiting.resolve(new ProjectReconciliationStep.ResolutionEvidence(
                "patch-2", "commit-2", "test-2", "review-2", "source-merge-2",
                ACTUAL_BASE, RESOLVED_TARGET), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("resolve");
        assertThatThrownBy(() -> waiting.recordProposal(new ProjectReconciliationStep.ReconciliationProposal(
                "workspace-reconciliation-1", "proposal-1", EXPECTED_BASE), NOW.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actualBaseCommit");

        ProjectReconciliationStep blocked = waiting.blockUnknown(NOW.plusSeconds(1));
        assertThat(blocked.state()).isEqualTo(ProjectReconciliationStepState.BLOCKED);
        assertThat(blocked.blockedCode()).isEqualTo("UNKNOWN_GIT_EFFECT");
        assertThat(blocked.blockUnknown(NOW.plusSeconds(2))).isSameAs(blocked);
        assertThatThrownBy(() -> blocked.recordProposal(new ProjectReconciliationStep.ReconciliationProposal(
                "workspace-reconciliation-1", "proposal-1", ACTUAL_BASE), NOW.plusSeconds(2)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("record proposal");
    }

    private ProjectReconciliationStep baseDrift() {
        return ProjectReconciliationStep.forBaseDrift(
                "reconciliation-1", "tenant-1", "owner-1", "project-1", "directory-1", "plan-1",
                "step-1", "execution-1", "barrier-1", "source-merge-1", EXPECTED_BASE, ACTUAL_BASE,
                "patch-1", "commit-1", "test-1", "review-1", NOW);
    }
}
