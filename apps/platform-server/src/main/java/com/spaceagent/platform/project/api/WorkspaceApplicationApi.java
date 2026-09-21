package com.spaceagent.platform.project.api;

import com.spaceagent.platform.project.domain.BridgeWorkspaceCommandState;
import com.spaceagent.platform.project.domain.WorkspaceMode;
import com.spaceagent.platform.project.domain.WorkspaceState;

import java.time.Instant;
import java.util.List;

public interface WorkspaceApplicationApi {
    WorkspaceView provision(ProvisionCommand command);
    WorkspaceView get(Query query);
    List<WorkspaceView> list(ListQuery query);
    List<BridgeCommandView> pendingBridgeCommands(BridgeCommandsQuery query);
    WorkspaceView completeBridge(CompleteBridgeCommand command);
    WorkspaceView archive(ArchiveCommand command);
    record ProvisionCommand(
            String tenantId,
            String userId,
            String projectId,
            String projectDirectoryId,
            String taskId,
            String sourceRepositoryId,
            String baseRef,
            String isolationKey) {

        public ProvisionCommand(
                String tenantId,
                String userId,
                String projectId,
                String taskId,
                String sourceRepositoryId,
                String baseRef,
                String isolationKey) {
            this(tenantId, userId, projectId, null, taskId, sourceRepositoryId,
                    baseRef, isolationKey);
        }

        public ProvisionCommand(
                String tenantId,
                String userId,
                String projectId,
                String taskId,
                String sourceRepositoryId,
                String baseRef) {
            this(tenantId, userId, projectId, null, taskId, sourceRepositoryId,
                    baseRef, "primary");
        }
    }
    record Query(String tenantId,String userId,String projectId,String workspaceId){}
    record ListQuery(String tenantId,String userId,String projectId){}
    record BridgeCommandsQuery(String tenantId,String userId,String bridgeId){}
    record CompleteBridgeCommand(String tenantId,String userId,String commandId,String bridgeToken,String opaqueLocator,String headCommit,boolean success,String failureReason){}
    record ArchiveCommand(String tenantId,String userId,String projectId,String workspaceId){}
    record WorkspaceView(
            String id,
            String projectId,
            String projectDirectoryId,
            String taskId,
            String sourceRepositoryId,
            String bridgeId,
            String isolationKey,
            WorkspaceMode mode,
            String worktreeKey,
            String baseRef,
            String branchName,
            String worktreeRef,
            String headCommit,
            boolean writable,
            WorkspaceState state,
            String failureReason,
            long revision,
            Instant createdAt,
            Instant updatedAt) {
        public WorkspaceView(
                String id, String projectId, String taskId, String sourceRepositoryId,
                String bridgeId, String isolationKey, WorkspaceMode mode, String worktreeKey,
                String baseRef, String branchName, String worktreeRef, String headCommit,
                boolean writable, WorkspaceState state, String failureReason, long revision,
                Instant createdAt, Instant updatedAt) {
            this(id, projectId, null, taskId, sourceRepositoryId, bridgeId, isolationKey,
                    mode, worktreeKey, baseRef, branchName, worktreeRef, headCommit,
                    writable, state, failureReason, revision, createdAt, updatedAt);
        }
    }
    record BridgeCommandView(String id,String workspaceId,String bridgeId,String rootHandle,String baseRef,String branchName,String worktreeKey,BridgeWorkspaceCommandState state,Instant createdAt){}
}
