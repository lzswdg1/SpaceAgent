package com.spaceagent.platform.project.api;

import com.spaceagent.platform.project.domain.ProjectLocalMaterializationManifestValidator;
import java.io.InputStream;
import java.time.Instant;

public interface LocalProjectMaterializationApplicationApi {
    SessionResult start(StartCommand command);
    SessionResult declareManifest(ManifestCommand command);
    ChunkResult stageChunk(ChunkCommand command, InputStream bytes);
    Result finalizeSnapshot(FinalizeCommand command);

    record StartCommand(String tenantId,String ownerUserId,String projectId,String bridgeId,
                        String bridgeDeviceId,String bridgeRootHandle,String requestId) { }
    record ManifestCommand(String tenantId,String ownerUserId,String projectId,String sessionId,
                           String requestId,String manifestSha256,String bridgeToken) {
        public ManifestCommand(String tenantId,String ownerUserId,String projectId,String sessionId,String requestId,String manifestSha256){this(tenantId,ownerUserId,projectId,sessionId,requestId,manifestSha256,null);}
    }
    record ChunkCommand(String tenantId,String ownerUserId,String projectId,String sessionId,String requestId,
                        String relativePath,long offset,long contentLength,String contentSha256,String bridgeToken) {
        public ChunkCommand(String tenantId,String ownerUserId,String projectId,String sessionId,String requestId,String relativePath,long offset,long contentLength,String contentSha256){this(tenantId,ownerUserId,projectId,sessionId,requestId,relativePath,offset,contentLength,contentSha256,null);}
    }
    record SessionResult(String sessionId,String state,String manifestSha256,Instant expiresAt,long revision) { }
    record ChunkResult(String requestId,String contentSha256,long contentLength) { }

    record FinalizeCommand(String tenantId, String ownerUserId, String projectId, String sessionId,
                           String requestId, String manifestSha256,
                           ProjectLocalMaterializationManifestValidator.Manifest manifest,String bridgeToken) {
        public FinalizeCommand(String tenantId,String ownerUserId,String projectId,String sessionId,String requestId,String manifestSha256,ProjectLocalMaterializationManifestValidator.Manifest manifest){this(tenantId,ownerUserId,projectId,sessionId,requestId,manifestSha256,manifest,null);}
    }

    record Result(String sessionId, String sourceRepositoryId, String snapshotRef,
                  String manifestSha256, String contentSha256) { }
}
