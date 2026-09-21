package com.spaceagent.platform.project.domain;

import java.util.Optional;

public interface SourceMergeRepository {
    void insert(SourceMerge merge);
    Optional<SourceMerge> findById(String id);
    Optional<SourceMerge> findByIdempotency(String tenantId, String userId, String idempotencyHash);
    boolean update(SourceMerge merge, long expectedRevision, SourceMergeState expectedState);
}
