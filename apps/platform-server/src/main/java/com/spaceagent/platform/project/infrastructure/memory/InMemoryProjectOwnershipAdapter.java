package com.spaceagent.platform.project.infrastructure.memory;

import com.spaceagent.platform.identity.api.IdentityOwnershipPort;
import com.spaceagent.platform.project.api.ProjectOwnershipPort;
import com.spaceagent.platform.project.domain.ProjectMembershipRepository;
import com.spaceagent.platform.project.domain.ProjectRepository;
import com.spaceagent.platform.project.domain.TaskRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Local/test ownership adapter with the same authorization semantics as PostgreSQL. */
@Component
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryProjectOwnershipAdapter implements ProjectOwnershipPort {

    private final ProjectRepository projectRepository;
    private final ProjectMembershipRepository membershipRepository;
    private final IdentityOwnershipPort identityOwnership;
    private final TaskRepository taskRepository;

    @Autowired
    public InMemoryProjectOwnershipAdapter(
            ProjectRepository projectRepository,
            ProjectMembershipRepository membershipRepository,
            IdentityOwnershipPort identityOwnership,
            TaskRepository taskRepository) {
        this.projectRepository = projectRepository;
        this.membershipRepository = membershipRepository;
        this.identityOwnership = identityOwnership;
        this.taskRepository = taskRepository;
    }

    public InMemoryProjectOwnershipAdapter(
            ProjectRepository projectRepository,
            ProjectMembershipRepository membershipRepository,
            IdentityOwnershipPort identityOwnership) {
        this(projectRepository, membershipRepository, identityOwnership, null);
    }

    @Override
    public boolean isOwnerOrMember(String projectId, String principalId) {
        return projectRepository.findById(projectId)
                .filter(project -> identityOwnership.isMemberOfTenant(project.tenantId(), principalId))
                .flatMap(project -> membershipRepository.findMembership(project.id(), principalId))
                .map(membership -> membership.role().canAccessProjectMemory())
                .orElse(false);
    }

    @Override
    public Optional<String> findProjectIdByTask(String taskId) {
        return taskRepository == null
                ? Optional.empty()
                : taskRepository.findById(taskId).map(task -> task.projectId());
    }

    @Override
    public Optional<String> findTenantIdByProject(String projectId) {
        return projectRepository.findById(projectId).map(project -> project.tenantId());
    }
}
