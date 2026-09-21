package com.spaceagent.platform.inference.api;

public record RemoveModelPoolMemberCommand(
        String tenantId,
        String userId,
        String poolId,
        String memberId) {
}
