package com.spaceagent.platform.runtime;

import com.spaceagent.platform.agent.api.*;
import com.spaceagent.platform.agent.domain.*;
import com.spaceagent.platform.inference.api.*;
import com.spaceagent.platform.knowledge.api.KnowledgeOwnershipPort;
import com.spaceagent.platform.runtime.api.ProjectPlanStepAssignmentValidationApplicationApi;
import com.spaceagent.platform.runtime.application.ProjectPlanStepAssignmentValidationService;
import com.spaceagent.platform.runtime.domain.ProjectPlanStepAssignment;
import com.spaceagent.platform.tooling.api.RuntimeCapabilityCatalogApplicationApi;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProjectPlanStepAssignmentValidationServiceTest {
    @Test void currentConfigurationAssignmentUsesAgentIdentityAndHashes() {
        var agents = mock(AgentApplicationApi.class);
        var current = mock(AgentCurrentConfigurationApplicationApi.class);
        var pools = mock(ModelPoolApplicationApi.class);
        var knowledge = mock(KnowledgeOwnershipPort.class);
        var catalog = mock(RuntimeCapabilityCatalogApplicationApi.class);
        for (String id : List.of("agent", "reviewer")) {
            AgentDefinitionView definition = mock(AgentDefinitionView.class);
            when(definition.id()).thenReturn(id);
            when(definition.tenantId()).thenReturn("tenant");
            when(definition.ownerId()).thenReturn("owner");
            when(definition.status()).thenReturn(AgentDefinitionStatus.ACTIVE);
            when(agents.findById(id)).thenReturn(Optional.of(definition));
            when(current.requireCurrent("tenant", "owner", id)).thenReturn(current(id));
        }
        when(pools.resolvePool("tenant", "owner", "pool"))
                .thenReturn(new ModelPoolResolutionView("pool", List.of()));
        when(knowledge.canAccess("knowledge", "owner")).thenReturn(true);
        when(catalog.resolveTools(List.of("tool")))
                .thenReturn(List.of(mock(RuntimeCapabilityCatalogApplicationApi.CapabilityView.class)));
        when(catalog.catalog()).thenReturn(new RuntimeCapabilityCatalogApplicationApi.CapabilityCatalogView(
                List.of(), List.of(), new RuntimeCapabilityCatalogApplicationApi.SandboxCapabilityView(
                "OCI", "isolated", true, true)));
        var service = new ProjectPlanStepAssignmentValidationService(
                agents, pools, knowledge, catalog, current);

        var evidence = service.resolve(new ProjectPlanStepAssignmentValidationApplicationApi.ResolveEvidenceCommand(
                "tenant", "owner", "agent", null, "reviewer", null, "pool"));

        assertThat(evidence.primaryConfigurationHash()).isNull();
        assertThat(evidence.reviewerConfigurationHash()).isNull();
    }

    @Test void validatesPublishedOwnerScopedCapabilities() {
        Fixture f = new Fixture();
        var validated = f.service.validate(f.assignment);
        assertThat(validated.modelPoolId()).isEqualTo("pool");
        assertThat(validated.capabilityHash()).matches("[0-9a-f]{64}");
        var tampered = ProjectPlanStepAssignment.fromPlanDefault("other", "tenant", "owner", "project", "plan",
                "other-step", "agent", "primary-hash", "reviewer", "reviewer-hash", "pool",
                "a".repeat(64), validated.configurationHash(), Instant.EPOCH);
        assertRequired(f.service, tampered);
    }

    @Test void rejectsCrossTenantAndMissingCurrentConfiguration() {
        Fixture f = new Fixture();
        when(f.agents.findById("agent")).thenReturn(Optional.empty());
        assertRequired(f.service, f.assignment);
        f = new Fixture();
        when(f.current.requireCurrent("tenant", "owner", "agent"))
                .thenThrow(new IllegalStateException("missing"));
        assertRequired(f.service, f.assignment);
    }

    @Test void rejectsMissingKnowledgeToolsSkillsAndSandbox() {
        Fixture f = new Fixture();
        when(f.knowledge.canAccess("knowledge", "owner")).thenReturn(false);
        assertRequired(f.service, f.assignment);
        f = new Fixture();
        when(f.catalog.resolveTools(List.of("tool"))).thenReturn(List.of());
        assertRequired(f.service, f.assignment);
        f = new Fixture();
        doThrow(new IllegalArgumentException("skill")).when(f.catalog)
                .validateSkillBindings("tenant", List.of("skill"), List.of("tool"));
        assertRequired(f.service, f.assignment);
        f = new Fixture();
        when(f.catalog.catalog()).thenReturn(new RuntimeCapabilityCatalogApplicationApi.CapabilityCatalogView(
                List.of(), List.of(), new RuntimeCapabilityCatalogApplicationApi.SandboxCapabilityView("x", "x", false, false)));
        assertRequired(f.service, f.assignment);
    }

    private static void assertRequired(ProjectPlanStepAssignmentValidationService service,
            ProjectPlanStepAssignment assignment) {
        assertThatThrownBy(() -> service.validate(assignment)).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getCode()).isEqualTo("ASSIGNMENT_REQUIRED"));
    }

    private static final class Fixture {
        final AgentApplicationApi agents = mock(AgentApplicationApi.class);
        final AgentCurrentConfigurationApplicationApi current = mock(AgentCurrentConfigurationApplicationApi.class);
        final ModelPoolApplicationApi pools = mock(ModelPoolApplicationApi.class);
        final KnowledgeOwnershipPort knowledge = mock(KnowledgeOwnershipPort.class);
        final RuntimeCapabilityCatalogApplicationApi catalog = mock(RuntimeCapabilityCatalogApplicationApi.class);
        final ProjectPlanStepAssignmentValidationService service = new ProjectPlanStepAssignmentValidationService(agents, pools, knowledge, catalog, current);
        final ProjectPlanStepAssignment assignment;
        Fixture() {
            agent("agent"); agent("reviewer");
            when(pools.resolvePool("tenant", "owner", "pool")).thenReturn(new ModelPoolResolutionView("pool", List.of()));
            when(knowledge.canAccess("knowledge", "owner")).thenReturn(true);
            when(catalog.resolveTools(List.of("tool"))).thenReturn(List.of(mock(RuntimeCapabilityCatalogApplicationApi.CapabilityView.class)));
            when(catalog.catalog()).thenReturn(new RuntimeCapabilityCatalogApplicationApi.CapabilityCatalogView(List.of(), List.of(), new RuntimeCapabilityCatalogApplicationApi.SandboxCapabilityView("OCI", "isolated", true, true)));
            var evidence = service.resolve(new ProjectPlanStepAssignmentValidationApplicationApi.ResolveEvidenceCommand(
                    "tenant", "owner", "agent", "primary-hash", "reviewer", "reviewer-hash", "pool"));
            assignment = ProjectPlanStepAssignment.fromPlanDefault("id", "tenant", "owner", "project", "plan",
                    "step", "agent", "primary-hash", "reviewer", "reviewer-hash", "pool",
                    evidence.capabilityHash(), evidence.configurationHash(), Instant.EPOCH);
        }
        void agent(String id) {
            AgentDefinitionView definition = mock(AgentDefinitionView.class); when(definition.id()).thenReturn(id); when(definition.tenantId()).thenReturn("tenant"); when(definition.ownerId()).thenReturn("owner"); when(definition.status()).thenReturn(AgentDefinitionStatus.ACTIVE); when(agents.findById(id)).thenReturn(Optional.of(definition));
            when(current.requireCurrent("tenant", "owner", id)).thenReturn(current(id));
        }
    }

    private static AgentCurrentConfigurationApplicationApi.ConfigurationView current(String agentId) {
        return new AgentCurrentConfigurationApplicationApi.ConfigurationView(
                agentId, "owner", "tenant", 2, "c".repeat(64), "pool", null, null,
                "prompt", 0.2, 200_000, 4096, 25, true, true, false,
                List.of("knowledge"), List.of("tool"), List.of("skill"), "private",
                List.of(), "owner", Instant.EPOCH);
    }
}
