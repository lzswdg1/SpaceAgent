package com.spaceagent.platform.runtime.domain;

import java.util.List;
import java.util.Optional;

public interface AgentReviewRepository {

    void save(AgentReview review);

    Optional<AgentReview> findById(String id);

    List<AgentReview> findByParentRunId(String parentRunId);
}
