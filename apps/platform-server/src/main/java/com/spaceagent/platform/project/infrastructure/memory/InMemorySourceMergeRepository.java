package com.spaceagent.platform.project.infrastructure.memory;

import com.spaceagent.platform.project.domain.SourceMerge;
import com.spaceagent.platform.project.domain.SourceMergeRepository;
import com.spaceagent.platform.project.domain.SourceMergeState;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemorySourceMergeRepository implements SourceMergeRepository {
    private final Map<String, SourceMerge> values = new ConcurrentHashMap<>();

    @Override
    public synchronized void insert(SourceMerge merge) {
        boolean duplicate = values.values().stream().anyMatch(value ->
                value.tenantId().equals(merge.tenantId())
                        && value.createdBy().equals(merge.createdBy())
                        && value.idempotencyHash().equals(merge.idempotencyHash()));
        if (duplicate || values.putIfAbsent(merge.id(), merge) != null) {
            throw new IllegalStateException("Source merge already exists");
        }
    }

    @Override
    public Optional<SourceMerge> findById(String id) {
        return Optional.ofNullable(values.get(id));
    }

    @Override
    public Optional<SourceMerge> findByIdempotency(
            String tenantId, String userId, String idempotencyHash) {
        return values.values().stream().filter(value ->
                value.tenantId().equals(tenantId)
                        && value.createdBy().equals(userId)
                        && value.idempotencyHash().equals(idempotencyHash)).findFirst();
    }

    @Override
    public synchronized boolean update(
            SourceMerge merge, long expectedRevision, SourceMergeState expectedState) {
        SourceMerge current = values.get(merge.id());
        if (current == null || current.revision() != expectedRevision
                || current.state() != expectedState) {
            return false;
        }
        values.put(merge.id(), merge);
        return true;
    }
}
