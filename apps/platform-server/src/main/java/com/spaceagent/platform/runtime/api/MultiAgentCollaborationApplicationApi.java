package com.spaceagent.platform.runtime.api;

import com.spaceagent.platform.runtime.domain.AgentDelegationState;
import com.spaceagent.platform.runtime.domain.AgentReviewDecision;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface MultiAgentCollaborationApplicationApi {

    DelegationView delegate(DelegateCommand command);

    ReviewView requestReview(ReviewCommand command);

    ReviewView decide(DecideReviewCommand command);

    ReviewView requestExecutionReview(ExecutionReviewCommand command);

    ReviewView decideExecutionReview(DecideReviewCommand command);

    Optional<ReviewView> review(String userId, String reviewId);

    List<DelegationView> delegations(String userId, String parentRunId);

    List<ReviewView> reviews(String userId, String parentRunId);

    record DelegateCommand(
            String userId,
            String parentRunId,
            String targetAgentId,
            String sourceRepositoryId,
            String baseRef) {}

    record ReviewCommand(
            String userId,
            String parentRunId,
            String childRunId,
            String reviewerAgentId,
            List<String> artifactIds) {

        public ReviewCommand {
            artifactIds = artifactIds == null ? List.of() : List.copyOf(artifactIds);
        }
    }

    record DecideReviewCommand(
            String userId,
            String reviewId,
            AgentReviewDecision decision,
            String evidence) {}

    record ExecutionReviewCommand(
            String userId,
            String codingRunId,
            String reviewerAgentId,
            List<String> artifactIds) {
        public ExecutionReviewCommand {
            artifactIds = artifactIds == null ? List.of() : List.copyOf(artifactIds);
        }
    }

    record DelegationView(
            String id,
            String parentRunId,
            String childRunId,
            String childTaskId,
            String targetAgentId,
            String workspaceId,
            String handoffId,
            AgentDelegationState state,
            Instant createdAt) {}

    record ReviewView(
            String id,
            String parentRunId,
            String childRunId,
            String reviewerAgentId,
            List<String> artifactIds,
            AgentReviewDecision decision,
            String evidence,
            Instant createdAt,
            Instant decidedAt) {}
}
