package com.spaceagent.platform.project.domain;
import java.time.Instant;
public record BridgeWorkspaceCommand(
        String id, String workspaceId, String bridgeId, String rootHandle,
        String baseRef, String branchName, String worktreeKey,
        BridgeWorkspaceCommandState state, Instant createdAt, Instant completedAt) { }
