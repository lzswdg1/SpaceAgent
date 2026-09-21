package com.spaceagent.platform.integration.application;

import com.spaceagent.platform.agent.api.AgentCleanupApplicationApi;
import com.spaceagent.platform.agent.api.AgentSystemAdministrationApi;
import com.spaceagent.platform.artifact.api.ArtifactCleanupApplicationApi;
import com.spaceagent.platform.automation.api.AutomationCleanupApplicationApi;
import com.spaceagent.platform.conversation.api.ConversationCleanupApplicationApi;
import com.spaceagent.platform.governance.api.GovernanceCleanupApplicationApi;
import com.spaceagent.platform.identity.api.IdentityUserCleanupApplicationApi;
import com.spaceagent.platform.identity.domain.UserCleanupStepKey;
import com.spaceagent.platform.inference.api.InferenceCleanupApplicationApi;
import com.spaceagent.platform.inference.api.InferenceSystemAdministrationApi;
import com.spaceagent.platform.knowledge.api.KnowledgeCleanupApplicationApi;
import com.spaceagent.platform.memory.api.MemoryCleanupApplicationApi;
import com.spaceagent.platform.project.api.ProjectCleanupApplicationApi;
import com.spaceagent.platform.project.api.ProjectSystemAdministrationApi;
import com.spaceagent.platform.runtime.api.RuntimeCleanupApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeSystemAdministrationApi;
import com.spaceagent.platform.tooling.api.ToolingCleanupApplicationApi;
import com.spaceagent.platform.tooling.api.ToolingSystemAdministrationApi;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class UserCleanupExecutionCoordinator {
    private final UserCleanupCoordinator control;
    private final IdentityUserCleanupApplicationApi identity;
    private final AutomationCleanupApplicationApi automation;
    private final RuntimeCleanupApplicationApi runtime;
    private final ArtifactCleanupApplicationApi artifact;
    private final ConversationCleanupApplicationApi conversation;
    private final MemoryCleanupApplicationApi memory;
    private final KnowledgeCleanupApplicationApi knowledge;
    private final ProjectCleanupApplicationApi project;
    private final AgentCleanupApplicationApi agent;
    private final InferenceCleanupApplicationApi inference;
    private final ToolingCleanupApplicationApi tooling;
    private final GovernanceCleanupApplicationApi governance;
    private final RuntimeSystemAdministrationApi runtimeEvidence;
    private final ProjectSystemAdministrationApi projectEvidence;
    private final AgentSystemAdministrationApi agentEvidence;
    private final InferenceSystemAdministrationApi inferenceEvidence;
    private final ToolingSystemAdministrationApi toolingEvidence;
    private final TimeProvider time;

    public UserCleanupExecutionCoordinator(
            UserCleanupCoordinator control, IdentityUserCleanupApplicationApi identity,
            AutomationCleanupApplicationApi automation, RuntimeCleanupApplicationApi runtime,
            ArtifactCleanupApplicationApi artifact, ConversationCleanupApplicationApi conversation,
            MemoryCleanupApplicationApi memory, KnowledgeCleanupApplicationApi knowledge,
            ProjectCleanupApplicationApi project, AgentCleanupApplicationApi agent,
            InferenceCleanupApplicationApi inference, ToolingCleanupApplicationApi tooling,
            GovernanceCleanupApplicationApi governance,
            RuntimeSystemAdministrationApi runtimeEvidence,
            ProjectSystemAdministrationApi projectEvidence,
            AgentSystemAdministrationApi agentEvidence,
            InferenceSystemAdministrationApi inferenceEvidence,
            ToolingSystemAdministrationApi toolingEvidence,
            TimeProvider time) {
        this.control = control; this.identity = identity; this.automation = automation;
        this.runtime = runtime; this.artifact = artifact; this.conversation = conversation;
        this.memory = memory; this.knowledge = knowledge; this.project = project;
        this.agent = agent; this.inference = inference; this.tooling = tooling;
        this.governance = governance; this.runtimeEvidence = runtimeEvidence;
        this.projectEvidence = projectEvidence; this.agentEvidence = agentEvidence;
        this.inferenceEvidence = inferenceEvidence; this.toolingEvidence = toolingEvidence;
        this.time = time;
    }

    public boolean runOnce(String workerId, int leaseSeconds) {
        var optional = control.claimNext(workerId, leaseSeconds);
        if (optional.isEmpty()) return false;
        var item = optional.orElseThrow();
        while (item.currentStep() != null) {
            try {
                Disposition disposition = execute(item);
                if (disposition == Disposition.DEFERRED || disposition == Disposition.BLOCKED) return true;
                control.recordStepCompleted(item);
                if (item.currentStep().stepKey() == UserCleanupStepKey.IDENTITY_FINALIZE_USER_TOMBSTONE) {
                    control.complete(item);
                    return true;
                }
                item = control.heartbeat(item, leaseSeconds);
            } catch (BusinessException error) {
                if ("USER_CLEANUP_OWNERSHIP_TRANSFER_REQUIRED".equals(error.getCode())) {
                    control.block(item, error.getCode(), "Explicit Organization ownership transfer is required");
                } else if ("ARTIFACT_BYTES_DELETE_BLOCKED".equals(error.getCode())) {
                    control.block(item,error.getCode(),"Artifact bytes deletion is blocked");
                } else {
                    control.fail(item, "OWNER_STEP_FAILED", "User cleanup owner step failed safely");
                }
                return true;
            } catch (RuntimeException error) {
                control.fail(item, "OWNER_STEP_FAILED", "User cleanup owner step failed safely");
                return true;
            }
        }
        control.complete(item);
        return true;
    }

    private Disposition execute(UserCleanupCoordinator.CleanupWorkItem item) {
        String userId = item.claim().job().userId();
        return switch (item.currentStep().stepKey()) {
            case AUTH_FREEZE -> { identity.freezeUser(userId); yield Disposition.READY; }
            case AUTOMATION_FREEZE_PURGE -> {
                var result = automation.cleanupUser(userId);
                if (result.blocked()) {
                    control.block(item, result.safeCode(), "UNKNOWN Automation effect blocks User cleanup");
                    yield Disposition.BLOCKED;
                }
                yield Disposition.READY;
            }
            case RUNTIME_QUIESCE -> quiesce(item, userId);
            case OWNERSHIP_MEMBERSHIP_RESOLUTION -> {
                var result = identity.resolveMemberships(userId);
                if (!result.ready()) {
                    control.defer(item, result.retryAt(), "ORGANIZATION_CLEANUP_PENDING",
                            "Waiting for sole-member Organization cleanup");
                    yield Disposition.DEFERRED;
                }
                yield Disposition.READY;
            }
            case ARTIFACT_PURGE -> {
                artifact.cleanupRuns(runtime.userRunIds(userId)); yield Disposition.READY;
            }
            case RUNTIME_PURGE -> { runtime.purgeUser(userId); yield Disposition.READY; }
            case CONVERSATION_PRIVATE_PURGE -> {
                conversation.cleanupUser(userId); yield Disposition.READY;
            }
            case USER_MEMORY_PURGE -> { memory.cleanupUserMemory(userId); yield Disposition.READY; }
            case KNOWLEDGE_PRIVATE_PURGE -> { var result=knowledge.cleanupUser(userId);if(result.blocked()){control.block(item,result.safeCode(),"Knowledge bytes deletion is blocked");yield Disposition.BLOCKED;}yield Disposition.READY; }
            case PROJECT_PRIVATE_RESOURCE_PURGE -> project(item, userId);
            case AGENT_PRIVATE_RESOURCE_PURGE -> agent(item, userId);
            case INFERENCE_PRIVATE_RESOURCE_PURGE -> inference(item, userId);
            case TOOLING_PRIVATE_RESOURCE_PURGE -> tooling(item, userId);
            case GOVERNANCE_PRIVATE_RESOURCE_PURGE -> {
                governance.cleanupUser(userId); yield Disposition.READY;
            }
            case IDENTITY_FINALIZE_USER_TOMBSTONE -> {
                identity.finalizeUser(userId); yield Disposition.READY;
            }
        };
    }

    private Disposition quiesce(UserCleanupCoordinator.CleanupWorkItem item, String userId) {
        var model = inferenceEvidence.deletionEvidence(userId);
        var tool = toolingEvidence.deletionEvidence(userId);
        if (model.unknownModelCalls() > 0 || tool.unknownToolExecutions() > 0) {
            control.block(item, "UNKNOWN_EFFECT_BLOCKER", "UNKNOWN Model or Tool effect blocks User cleanup");
            return Disposition.BLOCKED;
        }
        var runtimeState = runtimeEvidence.deletionEvidence(userId);
        var result = runtime.quiesceUser(userId);
        if (!result.ready() || runtimeState.recoveringRuns() > 0) {
            java.time.Instant retryAt = result.retryAt() == null
                    ? time.now().plusSeconds(1) : result.retryAt();
            control.defer(item, retryAt, "RUNTIME_LEASE_DRAIN", "Waiting for User Runtime lease drain");
            return Disposition.DEFERRED;
        }
        return Disposition.READY;
    }

    private Disposition project(UserCleanupCoordinator.CleanupWorkItem item, String userId) {
        var evidence = projectEvidence.deletionEvidence(userId);
        if (evidence.ownedProjects() > 0 || evidence.activeWorkspaces() > 0) {
            control.block(item, "PROJECT_OWNERSHIP_BLOCKER", "Project ownership must be resolved");
            return Disposition.BLOCKED;
        }
        project.cleanupUser(userId);
        return Disposition.READY;
    }

    private Disposition agent(UserCleanupCoordinator.CleanupWorkItem item, String userId) {
        if (agentEvidence.deletionEvidence(userId).ownedAgents() > 0) {
            control.block(item, "AGENT_OWNERSHIP_BLOCKER", "Agent ownership must be resolved");
            return Disposition.BLOCKED;
        }
        agent.cleanupUser(userId);
        return Disposition.READY;
    }

    private Disposition inference(UserCleanupCoordinator.CleanupWorkItem item, String userId) {
        var evidence = inferenceEvidence.deletionEvidence(userId);
        if (evidence.ownedProviders() > 0 || evidence.ownedModelPools() > 0) {
            control.block(item, "INFERENCE_OWNERSHIP_BLOCKER", "Inference ownership must be resolved");
            return Disposition.BLOCKED;
        }
        inference.cleanupUser(userId);
        return Disposition.READY;
    }

    private Disposition tooling(UserCleanupCoordinator.CleanupWorkItem item, String userId) {
        var evidence = toolingEvidence.deletionEvidence(userId);
        if (evidence.managedConnections() > 0 || evidence.activeCheckoutGrants() > 0) {
            control.block(item, "TOOLING_OWNERSHIP_BLOCKER", "MCP ownership or grants must be resolved");
            return Disposition.BLOCKED;
        }
        tooling.cleanupUser(userId);
        return Disposition.READY;
    }

    private enum Disposition { READY, DEFERRED, BLOCKED }
}
