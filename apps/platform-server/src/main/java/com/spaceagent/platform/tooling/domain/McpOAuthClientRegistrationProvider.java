package com.spaceagent.platform.tooling.domain;

import java.util.Optional;
import java.util.Set;

public interface McpOAuthClientRegistrationProvider {
    Set<String> authorizationServers();

    Optional<McpOAuthClientRegistration> findByAuthorizationServer(String authorizationServer);

    Optional<McpOAuthClientRegistration> findById(String id);
}
