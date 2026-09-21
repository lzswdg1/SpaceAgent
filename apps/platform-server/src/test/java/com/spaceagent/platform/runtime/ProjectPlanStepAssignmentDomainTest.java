package com.spaceagent.platform.runtime;

import com.spaceagent.platform.runtime.domain.ProjectPlanStepAssignment;
import com.spaceagent.platform.runtime.domain.ProjectPlanStepAssignmentSource;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryProjectPlanStepAssignmentRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectPlanStepAssignmentDomainTest {
    private static final Instant ASSIGNED_AT = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    void planDefaultIsMaterializedAsAnImmutablePerStepSnapshot() {
        ProjectPlanStepAssignment assignment = ProjectPlanStepAssignment.fromPlanDefault(
                "assignment-1", "tenant-1", "owner-1", "project-1", "plan-1", "step-1",
                "agent-1", "agent-version-1", "reviewer-1", "reviewer-version-1", "pool-1",
                "a".repeat(64), "b".repeat(64), ASSIGNED_AT);

        assertThat(assignment.source()).isEqualTo(ProjectPlanStepAssignmentSource.PLAN_DEFAULT);
        assertThat(assignment.revision()).isEqualTo(1);
        assertThat(assignment.assignmentHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void overrideAndCapabilityChangesProduceDifferentImmutableEvidence() {
        ProjectPlanStepAssignment defaultAssignment = ProjectPlanStepAssignment.fromPlanDefault(
                "assignment-1", "tenant-1", "owner-1", "project-1", "plan-1", "step-1",
                "agent-1", "agent-version-1", "reviewer-1", "reviewer-version-1", "pool-1",
                "a".repeat(64), "b".repeat(64), ASSIGNED_AT);
        ProjectPlanStepAssignment override = ProjectPlanStepAssignment.fromStepOverride(
                "assignment-2", "tenant-1", "owner-1", "project-1", "plan-1", "step-1",
                "agent-2", "agent-version-2", "reviewer-2", "reviewer-version-2", "pool-1",
                "c".repeat(64), "b".repeat(64), ASSIGNED_AT);

        assertThat(override.source()).isEqualTo(ProjectPlanStepAssignmentSource.STEP_OVERRIDE);
        assertThat(override.assignmentHash()).isNotEqualTo(defaultAssignment.assignmentHash());
    }

    @Test
    void reviewerSeparationAndCompleteHashEvidenceAreRequired() {
        assertThatThrownBy(() -> ProjectPlanStepAssignment.fromPlanDefault(
                "assignment-1", "tenant-1", "owner-1", "project-1", "plan-1", "step-1",
                "agent-1", "agent-version-1", "agent-1", "reviewer-version-1", "pool-1",
                "a".repeat(64), "b".repeat(64), ASSIGNED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reviewer must be distinct");

        assertThatThrownBy(() -> ProjectPlanStepAssignment.fromPlanDefault(
                "assignment-1", "tenant-1", "owner-1", "project-1", "plan-1", "step-1",
                "agent-1", "agent-version-1", "reviewer-1", "reviewer-version-1", "pool-1",
                "not-a-hash", "b".repeat(64), ASSIGNED_AT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capabilityHash");
    }

    @Test
    void memoryRepositoryScopesAndPreservesImmutableRevisions() {
        var repository = new InMemoryProjectPlanStepAssignmentRepository();
        ProjectPlanStepAssignment assignment = ProjectPlanStepAssignment.fromPlanDefault(
                "assignment-1", "tenant-1", "owner-1", "project-1", "plan-1", "step-1",
                "agent-1", "agent-version-1", "reviewer-1", "reviewer-version-1", "pool-1",
                "a".repeat(64), "b".repeat(64), ASSIGNED_AT);
        repository.insert(assignment);

        assertThat(repository.findLatest("tenant-1", "owner-1", "project-1", "plan-1", "step-1"))
                .contains(assignment);
        assertThat(repository.findLatest("tenant-1", "other-owner", "project-1", "plan-1", "step-1"))
                .isEmpty();
        assertThatThrownBy(() -> repository.insert(assignment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
    }
}
