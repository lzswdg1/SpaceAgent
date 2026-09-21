package com.spaceagent.platform.project.api;

import com.spaceagent.platform.project.domain.SourceRepositoryState;
import com.spaceagent.platform.project.domain.SourceRepositoryType;
import com.spaceagent.platform.project.domain.SourceRepositoryVisibility;
import java.time.Instant;

/** Secret-free and path-free source repository projection. */
public record SourceRepositoryView(
        String id, String projectId, String mcpConnectionId,
        String mcpInvocationId,
        String workspaceBridgeId,
        String providerRepositoryId, String displayName, String remoteUrl,
        String localRootHandle, String defaultBranch, SourceRepositoryType type,
        SourceRepositoryState state, SourceRepositoryVisibility visibility,
        String createdBy, Instant createdAt, Instant updatedAt,
        String materializationSessionId, String snapshotRef, String manifestSha256,
        String contentSha256, String finalizeRequestId) {
    public SourceRepositoryView(String id,String projectId,String mcpConnectionId,String mcpInvocationId,
            String workspaceBridgeId,String providerRepositoryId,String displayName,String remoteUrl,
            String localRootHandle,String defaultBranch,SourceRepositoryType type,SourceRepositoryState state,
            SourceRepositoryVisibility visibility,String createdBy,Instant createdAt,Instant updatedAt) {
        this(id,projectId,mcpConnectionId,mcpInvocationId,workspaceBridgeId,providerRepositoryId,displayName,
                remoteUrl,localRootHandle,defaultBranch,type,state,visibility,createdBy,createdAt,updatedAt,
                null,null,null,null,null);
    }
}
