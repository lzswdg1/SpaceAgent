package com.spaceagent.platform.integration.domain;
import java.time.Instant;
import java.util.Optional;
/** Integration-owned orchestration receipt; business objects remain owned by their existing modules. */
public interface ProjectStartRequestRepository {
    Optional<Request> find(String tenant,String owner,String kind,String keyHash);
    Optional<Request> pendingRoot(String tenant,String owner,String requestHash);
    boolean insert(Request request);
    boolean alias(Request request,String keyHash);
    void bindProject(String id,String projectId,Instant at);
    void complete(String id,String result,Instant at);
    record Request(String id,String tenantId,String ownerId,String kind,String keyHash,String requestHash,
            String state,String projectId,String result,Instant createdAt,Instant updatedAt) { }
}
