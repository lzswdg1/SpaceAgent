package com.spaceagent.platform.integration.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.artifact.api.ArtifactApplicationApi;
import com.spaceagent.platform.artifact.domain.ArtifactType;
import com.spaceagent.platform.governance.api.GovernanceApplicationApi;
import com.spaceagent.platform.governance.domain.GovernanceActionType;
import com.spaceagent.platform.integration.api.ReviewedSourceMergeApplicationApi;
import com.spaceagent.platform.project.api.SourceMergeApplicationApi;
import com.spaceagent.platform.runtime.api.MultiAgentCollaborationApplicationApi;
import com.spaceagent.platform.runtime.domain.AgentReviewDecision;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Cross-owner validation only; Project remains the sole merge state and Git authority. */
@Service
public class ReviewedSourceMergeApplicationService
        implements ReviewedSourceMergeApplicationApi {
    private final ArtifactApplicationApi artifacts;
    private final MultiAgentCollaborationApplicationApi collaboration;
    private final GovernanceApplicationApi governance;
    private final SourceMergeApplicationApi merges;
    private final ObjectMapper json;

    public ReviewedSourceMergeApplicationService(
            ArtifactApplicationApi artifacts,
            MultiAgentCollaborationApplicationApi collaboration,
            GovernanceApplicationApi governance,
            SourceMergeApplicationApi merges,
            ObjectMapper json) {
        this.artifacts = artifacts;
        this.collaboration = collaboration;
        this.governance = governance;
        this.merges = merges;
        this.json = json;
    }

    @Override
    public SourceMergeApplicationApi.SourceMergeView prepare(PrepareCommand command) {
        String idempotencyKey = bounded(command.idempotencyKey(), "Idempotency-Key", 200);
        if (idempotencyKey.length() < 8) {
            throw new IllegalArgumentException("Idempotency-Key must contain at least 8 characters");
        }
        ArtifactApplicationApi.ArtifactView proposal = artifacts.find(
                        command.tenantId(), command.commitProposalArtifactId())
                .filter(value -> value.type() == ArtifactType.COMMIT_PROPOSAL)
                .orElseThrow(() -> conflict(
                        "MERGE_COMMIT_PROPOSAL_NOT_FOUND", "Commit Proposal not found"));
        MultiAgentCollaborationApplicationApi.ReviewView review = collaboration.review(
                        command.userId(), command.reviewId())
                .filter(value -> value.decision() == AgentReviewDecision.APPROVED)
                .filter(value -> value.artifactIds().contains(proposal.id()))
                .filter(value -> value.childRunId().equals(proposal.agentRunId()))
                .orElseThrow(() -> conflict(
                        "MERGE_REVIEW_NOT_APPROVED",
                        "Approved Review does not cover the Commit Proposal"));
        requireEquals(command.projectId(), proposal.projectId(), "MERGE_PROJECT_SCOPE_MISMATCH");

        ArtifactMetadata metadata = metadata(proposal.metadataJson());
        ArtifactApplicationApi.ArtifactView patch = artifacts.find(
                        command.tenantId(), metadata.patchArtifactId())
                .filter(value -> value.type() == ArtifactType.PATCH)
                .orElseThrow(() -> conflict("MERGE_PATCH_ARTIFACT_NOT_FOUND", "Patch not found"));
        requireEquals(proposal.projectId(), patch.projectId(), "MERGE_ARTIFACT_SCOPE_MISMATCH");
        requireEquals(proposal.taskId(), patch.taskId(), "MERGE_ARTIFACT_SCOPE_MISMATCH");
        requireEquals(proposal.agentRunId(), patch.agentRunId(), "MERGE_ARTIFACT_SCOPE_MISMATCH");
        requireEquals(proposal.workspaceId(), patch.workspaceId(), "MERGE_ARTIFACT_SCOPE_MISMATCH");
        requireEquals(proposal.contentHash(), patch.contentHash(), "MERGE_PATCH_HASH_MISMATCH");
        if (!review.artifactIds().contains(patch.id())) {
            throw conflict("MERGE_REVIEW_EVIDENCE_INCOMPLETE",
                    "Approved Review does not cover the Patch Artifact");
        }

        Map<String, Object> exact = new LinkedHashMap<>();
        exact.put("tenantId", command.tenantId());
        exact.put("userId", command.userId());
        exact.put("projectId", command.projectId());
        exact.put("taskId", proposal.taskId());
        exact.put("agentRunId", proposal.agentRunId());
        exact.put("workspaceId", proposal.workspaceId());
        exact.put("reviewId", review.id());
        exact.put("commitProposalArtifactId", proposal.id());
        exact.put("patchArtifactId", patch.id());
        exact.put("baseHead", metadata.baseHead());
        exact.put("patchHash", patch.contentHash());
        exact.put("commitMessage", proposal.summary());
        String inputHash = hash(safeJson(exact));

        return merges.prepare(new SourceMergeApplicationApi.PrepareCommand(
                command.tenantId(), command.userId(), command.projectId(), proposal.taskId(),
                sourceRepositoryId(patch.metadataJson()), proposal.workspaceId(),
                proposal.agentRunId(), review.id(), proposal.id(), metadata.baseHead(),
                patch.contentHash(), bounded(proposal.summary(), "commitMessage", 500),
                hash(idempotencyKey), inputHash));
    }

    @Override
    public SourceMergeApplicationApi.SourceMergeView get(Query query) {
        return merges.get(new SourceMergeApplicationApi.Query(
                query.tenantId(), query.userId(), query.projectId(), query.mergeId()));
    }

    @Override
    public SourceMergeApplicationApi.SourceMergeView apply(ApplyCommand command) {
        SourceMergeApplicationApi.SourceMergeView merge = get(new Query(
                command.tenantId(), command.userId(), command.projectId(), command.mergeId()));
        if (merge.state() == com.spaceagent.platform.project.domain.SourceMergeState.APPLIED_LOCAL) {
            return merge;
        }
        GovernanceApplicationApi.AuthorizationView authorization = governance.authorize(
                new GovernanceApplicationApi.AuthorizeCommand(
                        command.tenantId(), command.userId(), GovernanceActionType.SOURCE_MERGE,
                        "SOURCE_MERGE", merge.id(), operationHash("APPLY", merge),
                        "Apply reviewed commit to local integration ref " + merge.targetRef(),
                        command.approvalId()));
        requireAuthorization(authorization);
        return merges.apply(new SourceMergeApplicationApi.ApplyCommand(
                command.tenantId(), command.userId(), command.projectId(), command.mergeId(),
                authorization.approval() == null ? null : authorization.approval().id()));
    }

    @Override
    public SourceMergeApplicationApi.SourceMergeView rollback(RollbackCommand command) {
        SourceMergeApplicationApi.SourceMergeView merge = get(new Query(
                command.tenantId(), command.userId(), command.projectId(), command.mergeId()));
        if (merge.state() == com.spaceagent.platform.project.domain.SourceMergeState.ROLLED_BACK) {
            return merge;
        }
        GovernanceApplicationApi.AuthorizationView authorization = governance.authorize(
                new GovernanceApplicationApi.AuthorizeCommand(
                        command.tenantId(), command.userId(), GovernanceActionType.SOURCE_MERGE,
                        "SOURCE_MERGE", merge.id(), operationHash("ROLLBACK", merge),
                        "Rollback local integration ref " + merge.targetRef(),
                        command.approvalId()));
        requireAuthorization(authorization);
        return merges.rollback(new SourceMergeApplicationApi.RollbackCommand(
                command.tenantId(), command.userId(), command.projectId(), command.mergeId(),
                authorization.approval() == null ? null : authorization.approval().id()));
    }

    @Override
    public SourceMergeApplicationApi.SourceMergeView reconcile(Query query) {
        return merges.reconcile(new SourceMergeApplicationApi.ReconcileCommand(
                query.tenantId(), query.userId(), query.projectId(), query.mergeId()));
    }

    private String sourceRepositoryId(String patchMetadata) {
        try {
            String sourceId = json.readTree(patchMetadata).path("sourceRepositoryId").asText(null);
            if (sourceId == null || sourceId.isBlank()) {
                throw new IllegalArgumentException();
            }
            return sourceId;
        } catch (Exception error) {
            throw conflict("MERGE_SOURCE_EVIDENCE_MISSING",
                    "Patch does not contain Source Repository evidence");
        }
    }

    private ArtifactMetadata metadata(String metadataJson) {
        try {
            var node = json.readTree(metadataJson);
            String base = node.path("baseHead").asText(null);
            String patch = node.path("patchArtifactId").asText(null);
            if (base == null || !base.matches("[0-9a-f]{40,64}")
                    || patch == null || patch.isBlank()) {
                throw new IllegalArgumentException();
            }
            return new ArtifactMetadata(base, patch);
        } catch (Exception error) {
            throw conflict("MERGE_PROPOSAL_EVIDENCE_INVALID",
                    "Commit Proposal evidence is invalid");
        }
    }

    private String operationHash(String action, SourceMergeApplicationApi.SourceMergeView merge) {
        return hash(safeJson(Map.of(
                "action", action,
                "mergeId", merge.id(),
                "targetRef", merge.targetRef(),
                "expectedBaseCommit", merge.expectedBaseCommit(),
                "preparedCommit", merge.preparedCommit(),
                "patchHash", merge.patchHash())));
    }

    private static void requireAuthorization(GovernanceApplicationApi.AuthorizationView authorization) {
        if (authorization.status() == GovernanceApplicationApi.AuthorizationStatus.APPROVAL_REQUIRED) {
            throw conflict("GOVERNANCE_APPROVAL_REQUIRED",
                    "Organization approval is required: " + authorization.approval().id());
        }
        if (!authorization.allowed()) {
            throw conflict("GOVERNANCE_APPROVAL_INVALID",
                    "Approval is missing, expired, consumed, or mismatched");
        }
    }

    private static void requireEquals(String expected, String actual, String code) {
        if (expected == null || !expected.equals(actual)) {
            throw conflict(code, "Reviewed merge evidence scope mismatch");
        }
    }

    private String safeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Unable to encode merge evidence", error);
        }
    }

    private static String hash(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("Unable to hash merge evidence", error);
        }
    }

    private static String bounded(String value, String field, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new IllegalArgumentException(field + " is required and must not exceed " + max);
        }
        return value.trim();
    }

    private static BusinessException conflict(String code, String message) {
        return new BusinessException(message, HttpStatus.CONFLICT, code);
    }

    private record ArtifactMetadata(String baseHead, String patchArtifactId) {}
}
