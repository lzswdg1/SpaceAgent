package com.spaceagent.platform.memory.api;

import java.util.List;

public interface MemoryCleanupApplicationApi {
    void cleanupProjectAndTaskMemory(List<String> projectIds, List<String> taskIds);
    void cleanupUserMemory(String userId);
}
