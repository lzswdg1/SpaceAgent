package com.spaceagent.admin.command.domain;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface AdminCommandRepository {
    boolean insert(AdminCommand command);

    Optional<AdminCommand> findById(UUID id);

    Optional<AdminCommand> findByOperationAndIdempotencyHash(String operation, String hash);

    PageResult page(int offset, int limit, String state, String operation, String targetId);

    boolean transition(UUID id, AdminCommandState expected, AdminCommandState next,
                       String platformReference, String resultJson, String safeErrorCode,
                       java.time.Instant at, boolean completed);

    record PageResult(List<AdminCommand> items, long total) {
        public PageResult { items = items == null ? List.of() : List.copyOf(items); }
    }
}
