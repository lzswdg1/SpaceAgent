package com.spaceagent.platform.runtime.application;

import com.spaceagent.platform.agent.api.AgentApplicationApi;
import com.spaceagent.platform.agent.api.AgentCurrentConfigurationApplicationApi;
import com.spaceagent.platform.agent.domain.AgentDefinitionStatus;
import com.spaceagent.platform.artifact.api.ArtifactApplicationApi;
import com.spaceagent.platform.artifact.domain.ArtifactType;
import com.spaceagent.platform.project.api.WorkspaceApplicationApi;
import com.spaceagent.platform.runtime.api.AcceptHandoffCommand;
import com.spaceagent.platform.runtime.api.AgentRunView;
import com.spaceagent.platform.runtime.api.CodingRuntimeApplicationApi;
import com.spaceagent.platform.runtime.api.CompleteHandoffCommand;
import com.spaceagent.platform.runtime.api.CreateHandoffCommand;
import com.spaceagent.platform.runtime.api.HandoffView;
import com.spaceagent.platform.runtime.api.MultiAgentCollaborationApplicationApi;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.platform.runtime.domain.AgentDelegation;
import com.spaceagent.platform.runtime.domain.AgentDelegationRepository;
import com.spaceagent.platform.runtime.domain.AgentDelegationState;
import com.spaceagent.platform.runtime.domain.AgentReview;
import com.spaceagent.platform.runtime.domain.AgentReviewDecision;
import com.spaceagent.platform.runtime.domain.AgentReviewRepository;
import com.spaceagent.platform.runtime.domain.AgentRunState;
import com.spaceagent.platform.runtime.domain.HandoffSnapshot;
import com.spaceagent.platform.runtime.domain.HandoffTestStatus;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class MultiAgentCollaborationApplicationService
        implements MultiAgentCollaborationApplicationApi {

    private static final Set<AgentRunState> TERMINAL_RUN_STATES =
            EnumSet.of(AgentRunState.COMPLETED, AgentRunState.FAILED, AgentRunState.CANCELLED);

    private final RuntimeApplicationApi runtime;
    private final CodingRuntimeApplicationApi coding;
    private final WorkspaceApplicationApi workspaces;
    private final ArtifactApplicationApi artifacts;
    private final AgentApplicationApi agents;
    private final AgentCurrentConfigurationApplicationApi currentConfigurations;
    private final AgentDelegationRepository delegations;
    private final AgentReviewRepository reviews;
    private final IdGenerator ids;
    private final TimeProvider time;

    public MultiAgentCollaborationApplicationService(
            RuntimeApplicationApi runtime,
            CodingRuntimeApplicationApi coding,
            WorkspaceApplicationApi workspaces,
            ArtifactApplicationApi artifacts,
            AgentApplicationApi agents,
            AgentCurrentConfigurationApplicationApi currentConfigurations,
            AgentDelegationRepository delegations,
            AgentReviewRepository reviews,
            IdGenerator ids,
            TimeProvider time) {
        this.runtime = runtime;
        this.coding = coding;
        this.workspaces = workspaces;
        this.artifacts = artifacts;
        this.agents = agents;
        this.currentConfigurations = currentConfigurations;
        this.delegations = delegations;
        this.reviews = reviews;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public DelegationView delegate(DelegateCommand command) {
        AgentRunView parent = requireActiveParent(command.userId(), command.parentRunId());
        requireOrganizationAgent(command.targetAgentId(), parent.tenantId(), command.userId());
        if (command.targetAgentId().equals(parent.agentId())) {
            throw conflict("Target Agent must be distinct from the parent Agent");
        }

        String delegationId = ids.nextId();
        var workspace = workspaces.provision(new WorkspaceApplicationApi.ProvisionCommand(
                parent.tenantId(),
                command.userId(),
                parent.projectId(),
                parent.taskId(),
                command.sourceRepositoryId(),
                command.baseRef(),
                "delegation:" + delegationId));
        try {
            var child = coding.start(new CodingRuntimeApplicationApi.StartCommand(
                    parent.tenantId(),
                    command.userId(),
                    command.targetAgentId(),
                    null,
                    parent.conversationId(),
                    parent.projectId(),
                    parent.taskId(),
                    parent.taskPlanId(),
                    parent.planStepId(),
                    workspace.id()));

            HandoffSnapshot snapshot = new HandoffSnapshot(
                    "Delegate " + parent.taskId(),
                    "Parent Run " + parent.id(),
                    List.of(),
                    List.of("child uses Workspace isolation key " + workspace.isolationKey()),
                    List.of(),
                    List.of(),
                    HandoffTestStatus.NOT_RUN,
                    List.of(),
                    List.of("execute child task", "produce patch, commit and test artifacts"));
            HandoffView handoff = runtime.createHandoff(
                    new CreateHandoffCommand(parent.id(), snapshot));
            runtime.acceptHandoff(new AcceptHandoffCommand(handoff.id(), child.agentRunId()));

            Instant now = time.now();
            AgentDelegation delegation = new AgentDelegation(
                    delegationId,
                    parent.tenantId(),
                    parent.id(),
                    child.agentRunId(),
                    parent.taskPlanId(),
                    parent.planStepId(),
                    parent.taskId(),
                    command.targetAgentId(),
                    workspace.id(),
                    handoff.id(),
                    AgentDelegationState.ACTIVE,
                    now,
                    now);
            delegations.save(delegation);
            return view(delegation);
        } catch (RuntimeException error) {
            try {
                workspaces.archive(new WorkspaceApplicationApi.ArchiveCommand(
                        parent.tenantId(), command.userId(), parent.projectId(), workspace.id()));
            } catch (RuntimeException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }

    @Override
    public ReviewView requestReview(ReviewCommand command) {
        AgentRunView parent = requireParent(command.userId(), command.parentRunId());
        AgentDelegation delegation = requireDelegation(parent.id(), command.childRunId());
        if (delegation.state() != AgentDelegationState.ACTIVE) {
            throw conflict("Delegation is not active");
        }
        AgentRunView child = runtime.findRun(command.childRunId())
                .filter(run -> run.tenantId().equals(parent.tenantId()))
                .filter(run -> run.projectId().equals(parent.projectId()))
                .filter(run -> run.taskId().equals(parent.taskId()))
                .orElseThrow(() -> conflict("Child Run scope mismatch"));
        if (child.state() != AgentRunState.COMPLETED) {
            throw conflict("Child Run must complete before review");
        }

        requireOrganizationAgent(command.reviewerAgentId(), parent.tenantId(), command.userId());
        if (command.reviewerAgentId().equals(delegation.targetAgentId())) {
            throw conflict("Reviewer must use a distinct active Agent");
        }

        List<ArtifactApplicationApi.ArtifactView> runArtifacts = artifacts.byRun(child.id());
        Set<String> ownedIds = new HashSet<>();
        runArtifacts.forEach(artifact -> ownedIds.add(artifact.id()));
        if (command.artifactIds() == null
                || command.artifactIds().isEmpty()
                || !ownedIds.containsAll(command.artifactIds())
                || new HashSet<>(command.artifactIds()).size() != command.artifactIds().size()) {
            throw conflict("Review artifacts are invalid or do not belong to child Run");
        }
        Set<ArtifactType> selectedTypes = runArtifacts.stream()
                .filter(artifact -> command.artifactIds().contains(artifact.id()))
                .map(ArtifactApplicationApi.ArtifactView::type)
                .collect(Collectors.toSet());
        if (!selectedTypes.contains(ArtifactType.PATCH)
                || !selectedTypes.contains(ArtifactType.COMMIT_PROPOSAL)) {
            throw conflict("Review requires Patch and Commit proposal artifacts");
        }

        Instant now = time.now();
        AgentReview review = new AgentReview(
                ids.nextId(),
                parent.tenantId(),
                parent.id(),
                child.id(),
                command.reviewerAgentId(),
                command.artifactIds(),
                AgentReviewDecision.PENDING,
                null,
                now,
                null);
        reviews.save(review);
        return view(review);
    }

    @Override
    public ReviewView decide(DecideReviewCommand command) {
        AgentReview review = reviews.findById(command.reviewId())
                .orElseThrow(() -> new BusinessException("Review not found", HttpStatus.NOT_FOUND));
        requireParent(command.userId(), review.parentRunId());
        if (review.decision() != AgentReviewDecision.PENDING
                || command.decision() == null
                || command.decision() == AgentReviewDecision.PENDING
                || command.evidence() == null
                || command.evidence().isBlank()) {
            throw conflict("Invalid review decision");
        }

        Instant now = time.now();
        AgentReview updated = review.decide(command.decision(), command.evidence(), now);
        reviews.save(updated);
        if (command.decision() == AgentReviewDecision.APPROVED) {
            AgentDelegation delegation = requireDelegation(review.parentRunId(), review.childRunId());
            runtime.completeHandoff(new CompleteHandoffCommand(delegation.handoffId()));
            delegations.save(delegation.withState(AgentDelegationState.COMPLETED, now));
        }
        return view(updated);
    }

    @Override
    public ReviewView requestExecutionReview(ExecutionReviewCommand command) {
        AgentRunView run = requireParent(command.userId(), command.codingRunId());
        if (run.state() != AgentRunState.IN_PROGRESS
                && run.state() != AgentRunState.WAITING_FOR_USER) {
            throw conflict("Coding Run must be active before execution review");
        }
        requireOrganizationAgent(command.reviewerAgentId(), run.tenantId(), command.userId());
        if (command.reviewerAgentId().equals(run.agentId())) {
            throw conflict("Reviewer must use a distinct active Agent");
        }
        List<ArtifactApplicationApi.ArtifactView> runArtifacts = artifacts.byRun(run.id());
        Set<String> requested = new HashSet<>(command.artifactIds());
        Set<String> owned = runArtifacts.stream().map(ArtifactApplicationApi.ArtifactView::id)
                .collect(Collectors.toSet());
        Set<ArtifactType> types = runArtifacts.stream()
                .filter(value -> requested.contains(value.id()))
                .map(ArtifactApplicationApi.ArtifactView::type).collect(Collectors.toSet());
        if (requested.isEmpty() || requested.size() != command.artifactIds().size()
                || !owned.containsAll(requested) || !types.contains(ArtifactType.PATCH)
                || !types.contains(ArtifactType.COMMIT_PROPOSAL)
                || !types.contains(ArtifactType.TEST_REPORT)) {
            throw conflict("Execution review requires owned Patch, Commit and Test artifacts");
        }
        AgentReview review = new AgentReview(ids.nextId(), run.tenantId(), run.id(), run.id(),
                command.reviewerAgentId(), command.artifactIds(),
                AgentReviewDecision.PENDING, null, time.now(), null);
        reviews.save(review);
        return view(review);
    }

    @Override
    public ReviewView decideExecutionReview(DecideReviewCommand command) {
        AgentReview review = reviews.findById(command.reviewId())
                .filter(value -> value.parentRunId().equals(value.childRunId()))
                .orElseThrow(() -> new BusinessException("Execution review not found", HttpStatus.NOT_FOUND));
        requireParent(command.userId(), review.parentRunId());
        if (review.decision() != AgentReviewDecision.PENDING || command.decision() == null
                || command.decision() == AgentReviewDecision.PENDING
                || command.evidence() == null || command.evidence().isBlank()) {
            throw conflict("Invalid execution review decision");
        }
        AgentReview updated = review.decide(
                command.decision(), command.evidence().trim(), time.now());
        reviews.save(updated);
        return view(updated);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReviewView> review(String userId, String reviewId) {
        return reviews.findById(reviewId)
                .filter(value -> {
                    requireParent(userId, value.parentRunId());
                    return true;
                })
                .map(MultiAgentCollaborationApplicationService::view);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DelegationView> delegations(String userId, String parentRunId) {
        requireParent(userId, parentRunId);
        return delegations.findByParentRunId(parentRunId).stream()
                .map(MultiAgentCollaborationApplicationService::view)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewView> reviews(String userId, String parentRunId) {
        requireParent(userId, parentRunId);
        return reviews.findByParentRunId(parentRunId).stream()
                .map(MultiAgentCollaborationApplicationService::view)
                .toList();
    }

    private AgentRunView requireActiveParent(String userId, String runId) {
        AgentRunView parent = requireParent(userId, runId);
        if (TERMINAL_RUN_STATES.contains(parent.state())) {
            throw conflict("Parent Run is terminal");
        }
        return parent;
    }

    private AgentRunView requireParent(String userId, String runId) {
        return runtime.findRun(runId)
                .filter(run -> run.ownerId().equals(userId))
                .filter(run -> run.projectId() != null)
                .filter(run -> run.taskPlanId() != null)
                .filter(run -> run.planStepId() != null)
                .orElseThrow(() -> new BusinessException("Parent Run not found", HttpStatus.NOT_FOUND));
    }

    private AgentDelegation requireDelegation(String parentRunId, String childRunId) {
        return delegations.findByParentRunId(parentRunId).stream()
                .filter(delegation -> delegation.childRunId().equals(childRunId))
                .findFirst()
                .orElseThrow(() -> conflict("Child Run is not delegated by this parent"));
    }

    private void requireOrganizationAgent(String agentId, String tenantId, String ownerId) {
        agents.findById(agentId)
                .filter(agent -> tenantId.equals(agent.tenantId()))
                .filter(agent -> agent.status() == AgentDefinitionStatus.ACTIVE)
                .orElseThrow(() -> conflict("Agent is not active in the parent Organization"));
        currentConfigurations.requireCurrent(tenantId, ownerId, agentId);
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(
                message,
                HttpStatus.CONFLICT,
                "MULTI_AGENT_COLLABORATION_CONFLICT");
    }

    private static DelegationView view(AgentDelegation delegation) {
        return new DelegationView(
                delegation.id(),
                delegation.parentRunId(),
                delegation.childRunId(),
                delegation.childTaskId(),
                delegation.targetAgentId(),
                delegation.workspaceId(),
                delegation.handoffId(),
                delegation.state(),
                delegation.createdAt());
    }

    private static ReviewView view(AgentReview review) {
        return new ReviewView(
                review.id(),
                review.parentRunId(),
                review.childRunId(),
                review.reviewerAgentId(),
                review.artifactIds(),
                review.decision(),
                review.evidence(),
                review.createdAt(),
                review.decidedAt());
    }
}
