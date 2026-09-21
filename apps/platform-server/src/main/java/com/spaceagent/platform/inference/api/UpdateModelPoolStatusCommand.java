package com.spaceagent.platform.inference.api;

public record UpdateModelPoolStatusCommand(
        String tenantId,
        String userId,
        String poolId) {
}
