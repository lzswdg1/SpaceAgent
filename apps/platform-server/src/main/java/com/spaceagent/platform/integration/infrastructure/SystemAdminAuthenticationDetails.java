package com.spaceagent.platform.integration.infrastructure;

public record SystemAdminAuthenticationDetails(
        String actorId, String scope, String requestId, String commandId) {
}
