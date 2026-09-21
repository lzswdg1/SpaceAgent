package com.spaceagent.platform.project.api;
import java.util.List;

public interface ProjectRootApplicationApi {
    List<RootView> list(String tenantId,String userId);
    RootView create(CreateCommand command);
    RootView prepare(String tenantId,String userId,String rootId);
    RootView archive(String tenantId,String userId,String rootId);
    RootView rename(String tenantId,String userId,String rootId,String name);
    record CreateCommand(String tenantId,String userId,String projectId,String sourceRepositoryId,String name) {}
    record RootView(String id,String projectId,String sourceRepositoryId,String name,String relativePath,
                    boolean defaultDirectory,String state,String rootKind,String storageState,String storageRef,
                    String tenantId,java.time.Instant createdAt,java.time.Instant updatedAt) {}
}
