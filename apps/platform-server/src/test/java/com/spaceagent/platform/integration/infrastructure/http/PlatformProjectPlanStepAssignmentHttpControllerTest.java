package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.runtime.api.ProjectPlanStepAssignmentApplicationApi;
import com.spaceagent.platform.runtime.domain.ProjectPlanStepAssignmentSource;
import com.spaceagent.shared.auth.TenantAuthenticationDetails;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PlatformProjectPlanStepAssignmentHttpControllerTest {
    @Test
    void projectsOnlyOwnerScopedImmutableAssignmentViewAndWriteCommand() {
        var assignments = mock(ProjectPlanStepAssignmentApplicationApi.class);
        var controller = new PlatformProjectPlanStepAssignmentHttpController(assignments);
        var authentication = new UsernamePasswordAuthenticationToken("owner", null, List.of());
        authentication.setDetails(new TenantAuthenticationDetails("tenant", "MEMBER"));
        var view = new ProjectPlanStepAssignmentApplicationApi.AssignmentView("id", "plan", "step", 1,
                ProjectPlanStepAssignmentSource.PLAN_DEFAULT, "agent", "version", "reviewer", "reviewer-version",
                "pool", "a".repeat(64), "b".repeat(64), "c".repeat(64), Instant.EPOCH);
        when(assignments.get(any())).thenReturn(view);
        when(assignments.definePlanDefault(any())).thenReturn(view);

        assertThat(controller.get("project", "plan", "step", authentication).data()).isEqualTo(view);
        controller.defineDefault("project", "plan", "step",
                new PlatformProjectPlanStepAssignmentHttpController.AssignmentRequest(
                        "agent", "reviewer", "pool"), authentication);

        verify(assignments).get(argThat(query -> query.tenantId().equals("tenant")
                && query.ownerId().equals("owner") && query.projectId().equals("project")));
        verify(assignments).definePlanDefault(argThat(command -> command.tenantId().equals("tenant")
                && command.ownerId().equals("owner") && command.planStepId().equals("step")
                && command.capabilityHash() == null && command.configurationHash() == null));
    }
}
