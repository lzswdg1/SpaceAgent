package com.spaceagent.platform.integration.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PlatformSystemAdministrationCommandRepository {
    default boolean hasSuccessfulOrganizationDeletion(String organizationId) { return false; }
    boolean insert(CommandRecord command);
    Optional<CommandRecord> findById(UUID commandId);
    Optional<CommandRecord> findByOperationAndIdempotencyHash(String operation, String hash);
    void complete(UUID commandId, String state, String resultJson, String safeErrorCode, Instant at);

    record CommandRecord(UUID commandId, String idempotencyHash, String operation,
                         String requestHash, UUID actorId, String targetUserId, String state,
                         String resultJson, String safeErrorCode, Instant createdAt,
                         Instant updatedAt, Instant completedAt) {
    }
}
