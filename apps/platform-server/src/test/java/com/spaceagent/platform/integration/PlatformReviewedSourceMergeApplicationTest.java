package com.spaceagent.platform.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.artifact.application.ArtifactApplicationService;
import com.spaceagent.platform.artifact.domain.ArtifactType;
import com.spaceagent.platform.artifact.infrastructure.memory.InMemoryArtifactRepository;
import com.spaceagent.platform.governance.api.GovernanceApplicationApi;
import com.spaceagent.platform.governance.domain.ApprovalState;
import com.spaceagent.platform.governance.domain.GovernanceActionType;
import com.spaceagent.platform.integration.api.ReviewedSourceMergeApplicationApi;
import com.spaceagent.platform.integration.application.ReviewedSourceMergeApplicationService;
import com.spaceagent.platform.project.api.SourceMergeApplicationApi;
import com.spaceagent.platform.project.domain.SourceMergeState;
import com.spaceagent.platform.runtime.api.MultiAgentCollaborationApplicationApi;
import com.spaceagent.platform.runtime.domain.AgentReviewDecision;
import com.spaceagent.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformReviewedSourceMergeApplicationTest {
    private static final String PROJECT = "00000000-0000-4000-8000-000000000002";
    private static final String TASK = "00000000-0000-4000-8000-000000000004";
    private static final String SOURCE = "00000000-0000-4000-8000-000000000001";
    private static final String WORKSPACE = "00000000-0000-4000-8000-000000000003";
    private static final String BASE = "a".repeat(40);
    private static final String PATCH_HASH = "sha256:" + "b".repeat(64);
    private static final Instant NOW = Instant.parse("2026-08-23T15:00:00Z");

    @Test
    void exactApprovedReviewPreparesAndGovernanceGatesApply() throws Exception {
        AtomicInteger ids = new AtomicInteger();
        var artifacts = new ArtifactApplicationService(
                new InMemoryArtifactRepository(),
                () -> "00000000-0000-4000-8000-%012d".formatted(ids.incrementAndGet()),
                () -> NOW);
        var patch = artifacts.create(new com.spaceagent.platform.artifact.api.ArtifactApplicationApi.CreateCommand(
                "tenant", PROJECT, TASK, "child-run", WORKSPACE, ArtifactType.PATCH,
                "workspace.patch", null, PATCH_HASH, "patch",
                new ObjectMapper().writeValueAsString(java.util.Map.of(
                        "sourceRepositoryId", SOURCE, "patch", "diff"))));
        var proposal = artifacts.create(new com.spaceagent.platform.artifact.api.ArtifactApplicationApi.CreateCommand(
                "tenant", PROJECT, TASK, "child-run", WORKSPACE, ArtifactType.COMMIT_PROPOSAL,
                "commit-proposal", null, PATCH_HASH, "implement reviewed change",
                new ObjectMapper().writeValueAsString(java.util.Map.of(
                        "baseHead", BASE, "patchArtifactId", patch.id()))));

        MultiAgentCollaborationApplicationApi collaboration =
                mock(MultiAgentCollaborationApplicationApi.class);
        when(collaboration.review("user", "review-1")).thenReturn(Optional.of(
                new MultiAgentCollaborationApplicationApi.ReviewView(
                        "review-1", "parent-run", "child-run", "reviewer-version",
                        List.of(patch.id(), proposal.id()), AgentReviewDecision.APPROVED,
                        "verified", NOW, NOW)));
        SourceMergeApplicationApi merges = mock(SourceMergeApplicationApi.class);
        SourceMergeApplicationApi.SourceMergeView view = view(proposal.id());
        when(merges.prepare(any())).thenReturn(view);
        when(merges.get(any())).thenReturn(view);
        when(merges.apply(any())).thenReturn(view);
        GovernanceApplicationApi governance = mock(GovernanceApplicationApi.class);
        var approval = approval("approval-1");
        when(governance.authorize(any()))
                .thenReturn(new GovernanceApplicationApi.AuthorizationView(
                        GovernanceApplicationApi.AuthorizationStatus.APPROVAL_REQUIRED, approval))
                .thenReturn(new GovernanceApplicationApi.AuthorizationView(
                        GovernanceApplicationApi.AuthorizationStatus.ALLOWED, approval));
        var service = new ReviewedSourceMergeApplicationService(
                artifacts, collaboration, governance, merges, new ObjectMapper());

        assertThat(service.prepare(new ReviewedSourceMergeApplicationApi.PrepareCommand(
                "tenant", "user", PROJECT, "review-1", proposal.id(),
                "idempotency-key-1")).id()).isEqualTo("merge-1");
        ArgumentCaptor<SourceMergeApplicationApi.PrepareCommand> prepare =
                ArgumentCaptor.forClass(SourceMergeApplicationApi.PrepareCommand.class);
        verify(merges).prepare(prepare.capture());
        assertThat(prepare.getValue().sourceRepositoryId()).isEqualTo(SOURCE);
        assertThat(prepare.getValue().patchHash()).isEqualTo(PATCH_HASH);
        assertThat(prepare.getValue().idempotencyHash())
                .matches("sha256:[0-9a-f]{64}");

        var apply = new ReviewedSourceMergeApplicationApi.ApplyCommand(
                "tenant", "user", PROJECT, "merge-1", null);
        assertThatThrownBy(() -> service.apply(apply))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.getCode()).isEqualTo("GOVERNANCE_APPROVAL_REQUIRED"));
        service.apply(new ReviewedSourceMergeApplicationApi.ApplyCommand(
                "tenant", "user", PROJECT, "merge-1", "approval-1"));
        ArgumentCaptor<GovernanceApplicationApi.AuthorizeCommand> authorization =
                ArgumentCaptor.forClass(GovernanceApplicationApi.AuthorizeCommand.class);
        verify(governance, org.mockito.Mockito.times(2)).authorize(authorization.capture());
        assertThat(authorization.getAllValues())
                .allSatisfy(value -> {
                    assertThat(value.actionType()).isEqualTo(GovernanceActionType.SOURCE_MERGE);
                    assertThat(value.resourceId()).isEqualTo("merge-1");
                    assertThat(value.operationHash()).matches("sha256:[0-9a-f]{64}");
                });
        assertThat(authorization.getAllValues().get(0).operationHash())
                .isEqualTo(authorization.getAllValues().get(1).operationHash());
    }

    private static SourceMergeApplicationApi.SourceMergeView view(String proposalId) {
        return new SourceMergeApplicationApi.SourceMergeView(
                "merge-1", PROJECT, TASK, SOURCE, WORKSPACE, "child-run", "review-1",
                proposalId, "refs/heads/main", BASE, PATCH_HASH, SourceMergeState.READY,
                "c".repeat(40), null, null, null, 1, false,
                "REMOTE_NOT_UPDATED", NOW, NOW, null, null);
    }

    private static GovernanceApplicationApi.ApprovalView approval(String id) {
        return new GovernanceApplicationApi.ApprovalView(
                id, "tenant", "user", GovernanceActionType.SOURCE_MERGE,
                "SOURCE_MERGE", "merge-1", "sha256:" + "1".repeat(64), "merge",
                ApprovalState.PENDING, NOW.plusSeconds(3600), null, null, null, null,
                1, NOW, NOW);
    }
}
