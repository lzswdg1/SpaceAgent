package com.spaceagent.platform.project.domain;

import java.util.List;
import java.util.Optional;

/** Project-owned chunk metadata. `(session, request)` is the stable replay boundary. */
public interface ProjectLocalMaterializationChunkRepository {
    ProjectLocalMaterializationChunk insertOrGet(ProjectLocalMaterializationChunk chunk);

    Optional<ProjectLocalMaterializationChunk> findByRequest(String sessionId, String requestId);

    List<ProjectLocalMaterializationChunk> findBySessionId(String sessionId);
}
