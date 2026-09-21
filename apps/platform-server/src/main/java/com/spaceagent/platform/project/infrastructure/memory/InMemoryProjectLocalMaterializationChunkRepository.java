package com.spaceagent.platform.project.infrastructure.memory;

import com.spaceagent.platform.project.domain.ProjectLocalMaterializationChunk;
import com.spaceagent.platform.project.domain.ProjectLocalMaterializationChunkRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryProjectLocalMaterializationChunkRepository
        implements ProjectLocalMaterializationChunkRepository {
    private final Map<String, ProjectLocalMaterializationChunk> byRequest = new ConcurrentHashMap<>();

    @Override
    public synchronized ProjectLocalMaterializationChunk insertOrGet(ProjectLocalMaterializationChunk chunk) {
        String key = key(chunk.sessionId(), chunk.requestId());
        ProjectLocalMaterializationChunk existing = byRequest.putIfAbsent(key, chunk);
        if (existing == null || samePayload(existing, chunk)) {
            return existing == null ? chunk : existing;
        }
        throw new IllegalStateException("Materialization chunk idempotency input changed");
    }

    @Override
    public Optional<ProjectLocalMaterializationChunk> findByRequest(String sessionId, String requestId) {
        return Optional.ofNullable(byRequest.get(key(sessionId, requestId)));
    }

    @Override
    public List<ProjectLocalMaterializationChunk> findBySessionId(String sessionId) {
        return byRequest.values().stream().filter(value -> value.sessionId().equals(sessionId))
                .sorted(Comparator.comparing(ProjectLocalMaterializationChunk::createdAt)
                        .thenComparing(ProjectLocalMaterializationChunk::id))
                .toList();
    }

    private static String key(String sessionId, String requestId) {
        return sessionId + "\n" + requestId;
    }

    static boolean samePayload(ProjectLocalMaterializationChunk left, ProjectLocalMaterializationChunk right) {
        return left.sessionId().equals(right.sessionId())
                && left.requestId().equals(right.requestId())
                && left.relativePath().equals(right.relativePath())
                && left.offset() == right.offset()
                && left.contentLength() == right.contentLength()
                && left.contentSha256().equals(right.contentSha256())
                && left.stagingKey().equals(right.stagingKey());
    }
}
