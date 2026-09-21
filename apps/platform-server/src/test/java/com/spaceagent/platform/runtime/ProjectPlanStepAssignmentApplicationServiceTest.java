package com.spaceagent.platform.runtime;

import com.spaceagent.platform.runtime.api.ProjectPlanStepAssignmentApplicationApi;
import com.spaceagent.platform.runtime.api.ProjectPlanStepAssignmentValidationApplicationApi;
import com.spaceagent.platform.runtime.application.ProjectPlanStepAssignmentApplicationService;
import com.spaceagent.platform.runtime.domain.ProjectPlanStepAssignmentSource;
import com.spaceagent.platform.runtime.infrastructure.memory.InMemoryProjectPlanStepAssignmentRepository;
import com.spaceagent.shared.id.UuidGenerator;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ProjectPlanStepAssignmentApplicationServiceTest {
    @Test
    void replaysSameDispatchBindingAndCreatesHigherHandoffRevision() {
        var repository = new InMemoryProjectPlanStepAssignmentRepository();
        var validation = mock(ProjectPlanStepAssignmentValidationApplicationApi.class);
        when(validation.resolve(any())).thenAnswer(invocation -> {
            var command = invocation.getArgument(0,
                    ProjectPlanStepAssignmentValidationApplicationApi.ResolveEvidenceCommand.class);
            return new ProjectPlanStepAssignmentValidationApplicationApi.ResolvedEvidenceView(
                    command.primaryConfigurationHash(), command.reviewerConfigurationHash(), command.modelPoolId(),
                    command.agentId().equals("agent") ? "a".repeat(64) : "c".repeat(64),
                    command.agentId().equals("agent") ? "b".repeat(64) : "d".repeat(64));
        });
        var service = new ProjectPlanStepAssignmentApplicationService(repository, validation,
                new UuidGenerator(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var dispatch = new ProjectPlanStepAssignmentApplicationApi.PlanDefaultAssignmentCommand(
                "tenant", "owner", "project", "plan", "step", "agent", "version",
                "reviewer", "reviewer-version", "pool", "a".repeat(64), "b".repeat(64));

        var first = service.definePlanDefault(dispatch);
        var replay = service.definePlanDefault(dispatch);
        var handoff = service.handoff(new ProjectPlanStepAssignmentApplicationApi.HandoffAssignmentCommand(
                "tenant", "owner", "project", "plan", "step", "agent-2", "version-2",
                "reviewer-2", "reviewer-version-2", "pool-2", "c".repeat(64), "d".repeat(64)));

        assertThat(replay.id()).isEqualTo(first.id());
        assertThat(handoff.revision()).isEqualTo(2);
        assertThat(handoff.source()).isEqualTo(ProjectPlanStepAssignmentSource.HANDOFF);
        assertThat(service.get(new ProjectPlanStepAssignmentApplicationApi.AssignmentQuery(
                "tenant", "owner", "project", "plan", "step")).id()).isEqualTo(handoff.id());
        verify(validation, times(3)).resolve(any());
    }

    @Test
    void rejectsCallerSelectedEvidenceInsteadOfPersistingIt() {
        var repository = new InMemoryProjectPlanStepAssignmentRepository();
        var validation = mock(ProjectPlanStepAssignmentValidationApplicationApi.class);
        when(validation.resolve(any())).thenReturn(
                new ProjectPlanStepAssignmentValidationApplicationApi.ResolvedEvidenceView(
                        "version", "reviewer-version", "pool", "a".repeat(64), "b".repeat(64)));
        var service = new ProjectPlanStepAssignmentApplicationService(repository, validation,
                new UuidGenerator(), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.definePlanDefault(
                new ProjectPlanStepAssignmentApplicationApi.PlanDefaultAssignmentCommand(
                        "tenant", "owner", "project", "plan", "step", "agent", "version",
                        "reviewer", "reviewer-version", "pool", "c".repeat(64), "b".repeat(64))))
                .hasMessage("Assignment evidence is stale");
        assertThat(repository.findLatest("tenant", "owner", "project", "plan", "step")).isEmpty();
    }
}
