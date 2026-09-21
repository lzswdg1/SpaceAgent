package com.spaceagent.platform.integration.api;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface PlatformSystemAdministrationCommandApi {
    CommandView execute(CommandRequest request);

    Optional<CommandView> command(UUID commandId);

    record CommandRequest(UUID commandId, String idempotencyHash, String operation,
                          String requestHash, String actorId, String targetUserId,
                          String reason, Map<String, String> input) {
        public CommandRequest { input = input == null ? Map.of() : Map.copyOf(input); }
    }

    record CommandView(UUID commandId, String operation, String targetUserId, String state,
                       Map<String, Object> result, String safeErrorCode,
                       String activationToken, Instant createdAt, Instant updatedAt,
                       Instant completedAt,
                       @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                       String passwordResetToken) {
        public CommandView(UUID commandId, String operation, String targetUserId, String state,
                Map<String,Object> result, String safeErrorCode, String activationToken, Instant createdAt,
                Instant updatedAt, Instant completedAt) {
            this(commandId, operation, targetUserId, state, result, safeErrorCode, activationToken,
                    createdAt, updatedAt, completedAt, null);
        }
        public CommandView { result = result == null ? Map.of() : Map.copyOf(result); }
    }
}
