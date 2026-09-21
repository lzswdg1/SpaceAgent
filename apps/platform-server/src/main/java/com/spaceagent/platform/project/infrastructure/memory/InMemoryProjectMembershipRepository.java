package com.spaceagent.platform.project.infrastructure.memory;

import com.spaceagent.platform.project.domain.ProjectMembership;
import com.spaceagent.platform.project.domain.ProjectMembershipRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Non-authoritative local/test Project membership adapter. */
@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryProjectMembershipRepository implements ProjectMembershipRepository {

    private final Map<String, ProjectMembership> values = new ConcurrentHashMap<>();

    @Override
    public void addMember(ProjectMembership membership) {
        values.put(key(membership.projectId(), membership.userId()), membership);
    }

    @Override
    public boolean removeMember(String projectId, String userId) {
        return values.remove(key(projectId, userId)) != null;
    }

    @Override
    public Optional<ProjectMembership> findMembership(String projectId, String userId) {
        return Optional.ofNullable(values.get(key(projectId, userId)));
    }

    @Override
    public List<ProjectMembership> listMembers(String projectId) {
        return values.values().stream()
                .filter(membership -> projectId.equals(membership.projectId()))
                .sorted(Comparator.comparing(ProjectMembership::createdAt)
                        .thenComparing(ProjectMembership::userId))
                .toList();
    }

    private static String key(String projectId, String userId) {
        return projectId + ":" + userId;
    }
}
