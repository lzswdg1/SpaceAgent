package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.identity.api.*;
import com.spaceagent.platform.identity.application.IdentityExecutionAuthorizationService;
import com.spaceagent.platform.identity.domain.*;
import com.spaceagent.platform.inference.api.InferenceExecutionApi;
import com.spaceagent.platform.inference.application.*;
import com.spaceagent.platform.inference.domain.*;
import com.spaceagent.platform.integration.application.IdentityInferenceExecutionAdmission;
import com.spaceagent.platform.integration.infrastructure.http.*;
import com.spaceagent.platform.memory.api.MemoryApplicationApi;
import com.spaceagent.platform.memory.domain.*;
import com.spaceagent.platform.project.api.ProjectOwnershipPort;
import com.spaceagent.platform.runtime.api.*;
import com.spaceagent.shared.auth.TenantAuthenticationDetails;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RuntimeActorAdmissionTest {
    @Test void currentTenantIsRequiredEvenForADualOrganizationMember() {
        var ownership = mock(ProjectOwnershipPort.class);
        when(ownership.findTenantIdByProject("project-B")).thenReturn(Optional.of("B"));
        when(ownership.findProjectIdByTask("task-B")).thenReturn(Optional.of("project-B"));
        when(ownership.isOwnerOrMember("project-B", "user")).thenReturn(true);
        var policy = new PlatformMemoryAuthorizationPolicy(ownership);
        assertThatThrownBy(() -> policy.requireScopeAccess(MemoryScopeRef.project("project-B"), "A", "user"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> policy.requireScopeAccess(MemoryScopeRef.task("task-B"), "A", "user"))
                .isInstanceOf(BusinessException.class);
        policy.requireScopeAccess(MemoryScopeRef.project("project-B"), "B", "user");
        policy.requireScopeAccess(MemoryScopeRef.user("user"), "A", "user");
        assertThatThrownBy(() -> policy.requireScopeAccess(MemoryScopeRef.user("other"), "A", "user"))
                .isInstanceOf(BusinessException.class);
    }

    @Test void organizationViewerCannotWriteMemoryEvenWhenProjectMembershipAllowsIt() {
        var memory = mock(MemoryApplicationApi.class);
        var controller = new PlatformMemoryHttpController(memory,
                new PlatformMemoryAuthorizationPolicy(mock(ProjectOwnershipPort.class)));
        var auth = new UsernamePasswordAuthenticationToken("user", "fixture", List.of());
        auth.setDetails(new TenantAuthenticationDetails("tenant", "VIEWER"));
        assertThatThrownBy(() -> controller.propose(new PlatformMemoryHttpController.ProposeRequest(
                MemoryScopeRef.project("project"), MemoryKind.PREFERENCE, "source", "TEST", "valid preference", .9, "key"), auth))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.review("candidate", new PlatformMemoryHttpController.ReviewRequest(
                MemoryCandidateState.ACCEPTED, "review"), auth)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(memory);
    }

    @Test void suspendedActorsAndDowngradedRolesCannotAdmitTheNextModelDispatch() {
        var identity = mock(IdentityApplicationApi.class);
        var activity = mock(IdentityActivityApplicationApi.class);
        var now = Instant.now();
        when(activity.isUserActive("user")).thenReturn(true);
        when(identity.findTenant("tenant")).thenReturn(Optional.of(new TenantView("tenant", "Tenant", "tenant", TenantStatus.ACTIVE, now)));
        when(identity.findTenantMembership("tenant", "user")).thenReturn(Optional.of(new TenantMembershipView(
                "tenant", "user", TenantRole.MEMBER, TenantMembershipStatus.ACTIVE, now, now)));
        var guard = new IdentityExecutionAuthorizationService(identity, activity);
        var runtime = mock(RuntimeApplicationApi.class);
        var run = mock(AgentRunView.class);
        when(run.tenantId()).thenReturn("tenant"); when(run.ownerId()).thenReturn("user");
        when(runtime.findRun("run")).thenReturn(Optional.of(run));
        var admission = new IdentityInferenceExecutionAdmission(runtime, guard);
        admission.requireDispatch("tenant", "run");
        assertThatThrownBy(() -> admission.requireDispatch("other", "run")).isInstanceOf(BusinessException.class);

        when(activity.isUserActive("user")).thenReturn(false);
        var calls = new AtomicInteger();
        var json = new ObjectMapper();
        var inference = new InferenceExecutionService(request -> { calls.incrementAndGet(); return new InferenceExecutor.InferenceExecution("unused", 1, 1); },
                null, new ModelCallRequestHasher(json), new ModelCallPayloadCodec(json), null, null, InferenceTelemetry.noop(), admission);
        var command = new InferenceExecutionApi.InferenceExecutionCommand("fixture", "model", List.of(), Map.of(),
                "run", "step", "call", "tenant", null, List.of(), "PRIORITY", null, false);
        assertThatThrownBy(() -> inference.execute(command)).isInstanceOf(BusinessException.class);
        assertThat(calls).hasValue(0);

        when(activity.isUserActive("user")).thenReturn(true);
        when(identity.findTenantMembership("tenant", "user")).thenReturn(Optional.of(new TenantMembershipView(
                "tenant", "user", TenantRole.VIEWER, TenantMembershipStatus.ACTIVE, now, now)));
        assertThatThrownBy(() -> admission.requireDispatch("tenant", "run")).isInstanceOf(BusinessException.class);
        guard.requireActiveActor("tenant", "user", false);
    }
}
