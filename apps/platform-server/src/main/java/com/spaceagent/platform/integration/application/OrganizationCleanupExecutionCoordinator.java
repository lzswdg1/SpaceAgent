package com.spaceagent.platform.integration.application;

import com.spaceagent.platform.agent.api.AgentCleanupApplicationApi;
import com.spaceagent.platform.artifact.api.ArtifactCleanupApplicationApi;
import com.spaceagent.platform.automation.api.AutomationCleanupApplicationApi;
import com.spaceagent.platform.conversation.api.ConversationCleanupApplicationApi;
import com.spaceagent.platform.governance.api.GovernanceCleanupApplicationApi;
import com.spaceagent.platform.identity.api.IdentityCleanupApplicationApi;
import com.spaceagent.platform.identity.api.OrganizationCleanupApplicationApi;
import com.spaceagent.platform.identity.domain.OrganizationCleanupStepKey;
import com.spaceagent.platform.inference.api.InferenceCleanupApplicationApi;
import com.spaceagent.platform.knowledge.api.KnowledgeCleanupApplicationApi;
import com.spaceagent.platform.memory.api.MemoryCleanupApplicationApi;
import com.spaceagent.platform.project.api.ProjectCleanupApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeCleanupApplicationApi;
import com.spaceagent.platform.tooling.api.ToolingCleanupApplicationApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class OrganizationCleanupExecutionCoordinator {
    private final OrganizationCleanupCoordinator control;
    private final OrganizationCleanupApplicationApi cleanup;
    private final AutomationCleanupApplicationApi automation;
    private final RuntimeCleanupApplicationApi runtime;
    private final ArtifactCleanupApplicationApi artifact;
    private final ConversationCleanupApplicationApi conversation;
    private final ToolingCleanupApplicationApi tooling;
    private final MemoryCleanupApplicationApi memory;
    private final ProjectCleanupApplicationApi project;
    private final AgentCleanupApplicationApi agent;
    private final InferenceCleanupApplicationApi inference;
    private final GovernanceCleanupApplicationApi governance;
    private final IdentityCleanupApplicationApi identity;
    private final KnowledgeCleanupApplicationApi knowledge;

    public OrganizationCleanupExecutionCoordinator(
            OrganizationCleanupCoordinator control,
            OrganizationCleanupApplicationApi cleanup,
            AutomationCleanupApplicationApi automation,
            RuntimeCleanupApplicationApi runtime,
            ArtifactCleanupApplicationApi artifact,
            ConversationCleanupApplicationApi conversation,
            ToolingCleanupApplicationApi tooling,
            MemoryCleanupApplicationApi memory,
            ProjectCleanupApplicationApi project,
            AgentCleanupApplicationApi agent,
            InferenceCleanupApplicationApi inference,
            GovernanceCleanupApplicationApi governance,
            IdentityCleanupApplicationApi identity) {
        this(control,cleanup,automation,runtime,artifact,conversation,tooling,memory,project,agent,inference,governance,identity,null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OrganizationCleanupExecutionCoordinator(
            OrganizationCleanupCoordinator control, OrganizationCleanupApplicationApi cleanup,
            AutomationCleanupApplicationApi automation, RuntimeCleanupApplicationApi runtime,
            ArtifactCleanupApplicationApi artifact, ConversationCleanupApplicationApi conversation,
            ToolingCleanupApplicationApi tooling, MemoryCleanupApplicationApi memory,
            ProjectCleanupApplicationApi project, AgentCleanupApplicationApi agent,
            InferenceCleanupApplicationApi inference, GovernanceCleanupApplicationApi governance,
            IdentityCleanupApplicationApi identity, KnowledgeCleanupApplicationApi knowledge) {
        this.control = control; this.cleanup = cleanup; this.automation = automation;
        this.runtime = runtime; this.artifact = artifact; this.conversation = conversation; this.tooling = tooling;
        this.memory = memory; this.project = project; this.agent = agent;
        this.inference = inference; this.governance = governance; this.identity = identity;
        this.knowledge=knowledge;
    }

    public boolean runOnce(String workerId, int leaseSeconds) {
        var optional = control.claimNext(workerId, leaseSeconds);
        if (optional.isEmpty()) return false;
        var item = optional.orElseThrow();
        if(project.hasRetainedCodeStorage(item.claim().job().organizationId())
                && (adminCommands==null || !adminCommands.hasSuccessfulOrganizationDeletion(item.claim().job().organizationId()))) {
            control.block(item,"PROJECT_STORAGE_ADMIN_REQUIRED","Physical code storage deletion requires SystemAdministrator authorization");
            return true;
        }
        while (item.currentStep() != null) {
            try {
                if (!execute(item)) return true;
                control.recordStepCompleted(item);
                if (item.currentStep().stepKey() == OrganizationCleanupStepKey.IDENTITY_FINALIZE) {
                    cleanup.complete(new OrganizationCleanupApplicationApi.CompleteCommand(
                            item.claim().job().organizationId(), item.claim().job().leaseOwner(),
                            item.claim().job().leaseToken(), item.claim().job().fencingToken()));
                    return true;
                }
                item = control.heartbeat(item, leaseSeconds);
            } catch (com.spaceagent.shared.exception.BusinessException failure) {
                if("ARTIFACT_BYTES_DELETE_BLOCKED".equals(failure.getCode()))control.block(item,failure.getCode(),"Artifact bytes deletion is blocked");
                else control.fail(item,"OWNER_STEP_FAILED","Cleanup owner step failed safely");
                return true;
            } catch (RuntimeException failure) {
                control.fail(item, "OWNER_STEP_FAILED",
                        "Cleanup step " + item.currentStep().stepKey().name() + " failed");
                return true;
            }
        }
        cleanup.complete(new OrganizationCleanupApplicationApi.CompleteCommand(
                item.claim().job().organizationId(), item.claim().job().leaseOwner(),
                item.claim().job().leaseToken(), item.claim().job().fencingToken()));
        return true;
    }

    private com.spaceagent.platform.integration.domain.PlatformSystemAdministrationCommandRepository adminCommands;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setStorageDeletionAuthorization(com.spaceagent.platform.integration.domain.PlatformSystemAdministrationCommandRepository repository){this.adminCommands=repository;}

    private boolean execute(OrganizationCleanupCoordinator.CleanupWorkItem item) {
        String id = item.claim().job().organizationId();
        switch (item.currentStep().stepKey()) {
            case AUTOMATION_FREEZE_PURGE -> automation.cleanupOrganization(id);
            case RUNTIME_QUIESCE -> {
                var result = runtime.quiesceOrganization(id);
                if (!result.ready()) {
                    control.defer(item, result.retryAt(), "RUNTIME_LEASE_DRAIN",
                            "Waiting for previously issued Runtime lease");
                    return false;
                }
            }
            case ARTIFACT_PURGE -> artifact.cleanupOrganization(id);
            case RUNTIME_PURGE -> runtime.purgeOrganization(id);
            case CONVERSATION_PURGE -> conversation.cleanupOrganization(id);
            case TOOLING_CONFIGURATION_PURGE -> tooling.cleanupOrganization(id);
            case PROJECT_TASK_MEMORY_PURGE -> {
                var scopes = project.resolveCleanupMemoryScopes(id);
                memory.cleanupProjectAndTaskMemory(scopes.projectIds(), scopes.taskIds());
            }
            case KNOWLEDGE_PURGE -> {
                if(knowledge!=null){var result=knowledge.cleanupOrganization(id);if(result.blocked()){control.block(item,result.safeCode(),"Knowledge bytes deletion is blocked");return false;}}
            }
            case PROJECT_EXTERNAL_AND_DATABASE_PURGE -> project.cleanupOrganization(id);
            case AGENT_PURGE -> agent.cleanupOrganization(id);
            case INFERENCE_PURGE -> inference.cleanupOrganization(id);
            case GOVERNANCE_PURGE -> governance.cleanupOrganization(id);
            case IDENTITY_FINALIZE -> identity.finalizeOrganization(id);
        }
        return true;
    }
}
