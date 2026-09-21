package com.spaceagent.platform.runtime.infrastructure.memory;

import com.spaceagent.platform.runtime.domain.AgentReview;
import com.spaceagent.platform.runtime.domain.AgentReviewRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryAgentReviewRepository implements AgentReviewRepository {

    private final Map<String, AgentReview> values = new ConcurrentHashMap<>();

    @Override
    public void save(AgentReview review) {
        values.put(review.id(), review);
    }

    @Override
    public Optional<AgentReview> findById(String id) {
        return Optional.ofNullable(values.get(id));
    }

    @Override
    public List<AgentReview> findByParentRunId(String parentRunId) {
        return values.values().stream()
                .filter(review -> review.parentRunId().equals(parentRunId))
                .sorted(Comparator.comparing(AgentReview::createdAt).thenComparing(AgentReview::id))
                .toList();
    }
}
