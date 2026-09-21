package com.spaceagent.platform.knowledge.api;

import com.spaceagent.platform.knowledge.domain.DocumentWorkspace;
import java.time.Instant;import java.util.List;

public interface DocumentWorkspaceApplicationApi {
    WorkspaceView create(CreateCommand command);
    WorkspaceView get(GetQuery query);
    List<WorkspaceView> list(ListQuery query);
    WorkspaceView changeQuota(ChangeQuotaCommand command);
    WorkspaceView pause(LifecycleCommand command);
    WorkspaceView resume(LifecycleCommand command);
    WorkspaceView archive(LifecycleCommand command);
    record CreateCommand(String tenantId,String actorUserId,DocumentWorkspace.ScopeType scopeType,
            String scopeId,String name,DocumentWorkspace.Quota quota,String idempotencyKey) {}
    record GetQuery(String tenantId,String actorUserId,String workspaceId) {}
    record ListQuery(String tenantId,String actorUserId,DocumentWorkspace.ScopeType scopeType) {}
    record ChangeQuotaCommand(String tenantId,String actorUserId,String workspaceId,
            DocumentWorkspace.Quota quota,long expectedRevision) {}
    record LifecycleCommand(String tenantId,String actorUserId,String workspaceId,long expectedRevision) {}
    record WorkspaceView(String id,String scopeType,String scopeId,String ownerId,String name,
            String objectNamespace,DocumentWorkspace.Quota quota,DocumentWorkspace.Usage usage,
            String state,long revision,Instant createdAt,Instant updatedAt,Instant archivedAt) {}
}
