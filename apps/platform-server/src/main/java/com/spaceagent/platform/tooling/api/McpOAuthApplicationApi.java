package com.spaceagent.platform.tooling.api;

import com.spaceagent.platform.tooling.domain.McpConnectionState;

import java.time.Instant;
import java.util.Set;

public interface McpOAuthApplicationApi {
    AuthorizationView begin(BeginCommand command);

    GrantView complete(CompleteCommand command);

    record BeginCommand(
            String tenantId, String userId, String connectionId, String redirectUri) {
    }

    record CompleteCommand(
            String tenantId, String userId, String state, String code, String error) {
        public CompleteCommand(String tenantId, String userId, String state, String code) {
            this(tenantId, userId, state, code, null);
        }
    }

    record AuthorizationView(
            String state,
            String authorizationUrl,
            String authorizationServer,
            String clientRegistrationId,
            Instant expiresAt) {
    }

    record GrantView(
            String connectionId,
            McpConnectionState state,
            Instant tokenExpiresAt,
            Set<String> grantedScopes) {
        public GrantView {
            grantedScopes = grantedScopes == null ? Set.of() : Set.copyOf(grantedScopes);
        }
    }
}
