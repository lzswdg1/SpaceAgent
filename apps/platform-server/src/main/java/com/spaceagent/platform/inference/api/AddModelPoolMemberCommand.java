package com.spaceagent.platform.inference.api;

public record AddModelPoolMemberCommand(
        String tenantId,
        String userId,
        String poolId,
        String providerId,
        String providerModelId,
        Integer priority,
        Integer weight) {
}
