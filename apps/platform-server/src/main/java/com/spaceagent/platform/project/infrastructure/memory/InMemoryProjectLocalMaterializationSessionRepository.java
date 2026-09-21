package com.spaceagent.platform.project.infrastructure.memory;

import com.spaceagent.platform.project.domain.ProjectLocalMaterializationSession;
import com.spaceagent.platform.project.domain.ProjectLocalMaterializationSessionRepository;
import com.spaceagent.platform.project.domain.ProjectLocalMaterializationSessionState;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryProjectLocalMaterializationSessionRepository
        implements ProjectLocalMaterializationSessionRepository {
    private final Map<String, ProjectLocalMaterializationSession> values = new ConcurrentHashMap<>();

    @Override
    public synchronized void insert(ProjectLocalMaterializationSession session) {
        boolean duplicate = values.values().stream().anyMatch(value -> sameRequest(value, session));
        if (duplicate || values.putIfAbsent(session.id(), session) != null) {
            throw new IllegalStateException("Local materialization session already exists");
        }
    }

    @Override
    public Optional<ProjectLocalMaterializationSession> findById(
            String tenantId, String ownerUserId, String projectId, String sessionId) {
        return Optional.ofNullable(values.get(sessionId)).filter(value -> scoped(value, tenantId, ownerUserId, projectId));
    }

    @Override
    public Optional<ProjectLocalMaterializationSession> findByRequest(
            String tenantId, String ownerUserId, String projectId, String bridgeId, String requestId) {
        return values.values().stream()
                .filter(value -> scoped(value, tenantId, ownerUserId, projectId))
                .filter(value -> value.bridgeId().equals(bridgeId) && value.requestId().equals(requestId))
                .findFirst();
    }

    @Override
    public List<ProjectLocalMaterializationSession> findActiveExpiredBefore(Instant now, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return values.values().stream()
                .filter(value -> !value.isTerminal() && value.state() != ProjectLocalMaterializationSessionState.BLOCKED)
                .filter(value -> !value.expiresAt().isAfter(now))
                .sorted(Comparator.comparing(ProjectLocalMaterializationSession::expiresAt)
                        .thenComparing(ProjectLocalMaterializationSession::id))
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized boolean update(
            ProjectLocalMaterializationSession session,
            long expectedRevision,
            ProjectLocalMaterializationSessionState expectedState) {
        ProjectLocalMaterializationSession current = values.get(session.id());
        if (current == null || current.revision() != expectedRevision || current.state() != expectedState) {
            return false;
        }
        values.put(session.id(), session);
        return true;
    }

    private static boolean sameRequest(
            ProjectLocalMaterializationSession left, ProjectLocalMaterializationSession right) {
        return left.tenantId().equals(right.tenantId())
                && left.ownerUserId().equals(right.ownerUserId())
                && left.projectId().equals(right.projectId())
                && left.bridgeId().equals(right.bridgeId())
                && left.requestId().equals(right.requestId());
    }

    private static boolean scoped(
            ProjectLocalMaterializationSession value, String tenantId, String ownerUserId, String projectId) {
        return value.tenantId().equals(tenantId)
                && value.ownerUserId().equals(ownerUserId)
                && value.projectId().equals(projectId);
    }
}
