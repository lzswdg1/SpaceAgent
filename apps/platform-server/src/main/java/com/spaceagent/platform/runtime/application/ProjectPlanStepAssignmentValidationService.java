package com.spaceagent.platform.runtime.application;

import com.spaceagent.platform.agent.api.AgentApplicationApi;
import com.spaceagent.platform.agent.api.AgentCurrentConfigurationApplicationApi;
import com.spaceagent.platform.agent.domain.AgentDefinitionStatus;
import com.spaceagent.platform.inference.api.ModelPoolApplicationApi;
import com.spaceagent.platform.knowledge.api.KnowledgeOwnershipPort;
import com.spaceagent.platform.runtime.api.ProjectPlanStepAssignmentValidationApplicationApi;
import com.spaceagent.platform.runtime.domain.ProjectPlanStepAssignment;
import com.spaceagent.platform.tooling.api.RuntimeCapabilityCatalogApplicationApi;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Fail-closed public-API validation for a new Runtime assignment snapshot. */
@Service
public class ProjectPlanStepAssignmentValidationService
        implements ProjectPlanStepAssignmentValidationApplicationApi {
    private final AgentApplicationApi agents;
    private final ModelPoolApplicationApi pools;
    private final KnowledgeOwnershipPort knowledge;
    private final RuntimeCapabilityCatalogApplicationApi catalog;
    private final AgentCurrentConfigurationApplicationApi currentConfigurations;

    @Autowired
    public ProjectPlanStepAssignmentValidationService(AgentApplicationApi agents,
            ModelPoolApplicationApi pools,
            KnowledgeOwnershipPort knowledge, RuntimeCapabilityCatalogApplicationApi catalog,
            AgentCurrentConfigurationApplicationApi currentConfigurations) {
        this.agents = agents; this.pools = pools;
        this.knowledge = knowledge; this.catalog = catalog;
        this.currentConfigurations = currentConfigurations;
    }

    @Override
    public ResolvedEvidenceView resolve(ResolveEvidenceCommand command) {
        try {
        AssignmentConfiguration primary = validateConfiguration(
                command.tenantId(), command.ownerId(), command.agentId(), command.primaryConfigurationHash());
        validateConfiguration(command.tenantId(), command.ownerId(), command.reviewerAgentId(),
                command.reviewerConfigurationHash());
        if (!java.util.Objects.equals(command.modelPoolId(),primary.modelPoolId())) fail();
        if(command.modelPoolId()!=null)pools.resolvePool(command.tenantId(), command.ownerId(), command.modelPoolId());
        else {
            var direct=agents.runtimeConfiguration(command.tenantId(),command.ownerId(),command.agentId());
            if(direct.modelProviderId()==null||direct.modelId()==null)fail();
        }
        primary.knowledgeBaseIds().forEach(id -> { if (!knowledge.canAccess(id, command.ownerId())) fail(); });
        if (catalog.resolveTools(primary.enabledToolIds()).size() != primary.enabledToolIds().size()) fail();
        catalog.validateSkillBindings(command.tenantId(), primary.skillIds(), primary.enabledToolIds());
        var sandbox = catalog.catalog().sandbox();
        if (!sandbox.containerized() || !sandbox.codingCommandAvailable()) fail();
        String capabilityHash = capabilityHash(primary, sandbox.mode(), sandbox.isolation());
        return new ResolvedEvidenceView(command.primaryConfigurationHash(), command.reviewerConfigurationHash(),
                command.modelPoolId(), capabilityHash, primary.configHash());
        } catch (BusinessException error) {
            if ("ASSIGNMENT_REQUIRED".equals(error.getCode())) throw error;
            throw failure();
        } catch (RuntimeException error) {
            throw failure();
        }
    }

    @Override
    public ValidationView validate(ProjectPlanStepAssignment assignment) {
        ResolvedEvidenceView resolved = resolve(new ResolveEvidenceCommand(
                assignment.tenantId(), assignment.ownerId(), assignment.agentId(),
                assignment.primaryConfigurationHash(), assignment.reviewerAgentId(),
                assignment.reviewerConfigurationHash(), assignment.modelPoolId()));
        if (!assignment.capabilityHash().equals(resolved.capabilityHash())
                || !assignment.configurationHash().equals(resolved.configurationHash())) fail();
        return new ValidationView(assignment.id(), assignment.primaryConfigurationHash(),
                assignment.reviewerConfigurationHash(), assignment.modelPoolId(),
                assignment.capabilityHash(), assignment.configurationHash());
    }

    private AssignmentConfiguration validateConfiguration(String tenantId, String ownerId,
            String agentId, String versionId) {
        var definition = agents.findById(agentId).filter(value -> tenantId.equals(value.tenantId()))
                .filter(value -> ownerId.equals(value.ownerId()))
                .filter(value -> value.status() == AgentDefinitionStatus.ACTIVE).orElseThrow(this::failure);
        var current = currentConfigurations.requireCurrent(tenantId, ownerId, agentId);
        return new AssignmentConfiguration(
                current.modelPoolId(), current.knowledgeBaseIds(), current.enabledToolIds(),
                current.skillIds(), current.configHash());
    }

    private static String capabilityHash(
            AssignmentConfiguration value, String sandboxMode, String sandboxIsolation) {
        String content = String.join("\n", value.knowledgeBaseIds()) + "|"
                + String.join("\n", value.enabledToolIds()) + "|"
                + String.join("\n", value.skillIds()) + "|" + sandboxMode + "|" + sandboxIsolation;
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private void fail() { throw failure(); }
    private BusinessException failure() {
        return new BusinessException("Assignment capability is required", HttpStatus.CONFLICT,
                "ASSIGNMENT_REQUIRED");
    }

    private record AssignmentConfiguration(
            String modelPoolId,
            java.util.List<String> knowledgeBaseIds,
            java.util.List<String> enabledToolIds,
            java.util.List<String> skillIds,
            String configHash) {
    }
}
