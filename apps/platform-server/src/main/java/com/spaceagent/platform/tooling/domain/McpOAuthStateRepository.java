package com.spaceagent.platform.tooling.domain;

import java.time.Instant;
import java.util.Optional;

public interface McpOAuthStateRepository {
    void save(McpOAuthState state);

    Optional<McpOAuthState> consume(
            String stateHash, String tenantId, String userId, Instant now);

    void redact(String id, Instant now);
}
