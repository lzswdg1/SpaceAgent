package com.spaceagent.platform.project.domain;

import java.io.InputStream;

/** Isolated server-managed temporary byte store. It never derives a filesystem path from client metadata. */
public interface ProjectLocalMaterializationChunkStaging {
    StoredChunk stage(String sessionId, String stagingKey, long contentLength, String contentSha256, InputStream bytes);

    InputStream open(String sessionId, String stagingKey);
    void cleanupSession(String sessionId);

    record StoredChunk(String stagingKey, long contentLength, String contentSha256) { }
}
