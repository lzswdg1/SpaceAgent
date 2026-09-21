package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.memory.domain.MemoryScopeRef;
import com.spaceagent.platform.project.api.ProjectOwnershipPort;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Single HTTP authorization policy for every scoped Memory operation.
 */
@Component
public class PlatformMemoryAuthorizationPolicy {

    private final ProjectOwnershipPort projectOwnership;

    public PlatformMemoryAuthorizationPolicy(ProjectOwnershipPort projectOwnership) {
        this.projectOwnership = projectOwnership;
    }

    public void requireScopeAccess(MemoryScopeRef scope, String tenantId, String principalId) {
        if (!canAccess(scope, tenantId, principalId)) {
            throw notFound();
        }
    }

    public String requireTaskProjectAccess(
            String taskId,
            String requestedProjectId,
            String tenantId,
            String principalId) {
        String projectId = projectOwnership.findProjectIdByTask(taskId)
                .filter(value -> !value.isBlank())
                .orElseThrow(this::notFound);
        if (requestedProjectId != null && !requestedProjectId.isBlank()
                && !projectId.equals(requestedProjectId)) {
            throw notFound();
        }
        if (!canAccessProject(projectId, tenantId, principalId)) {
            throw notFound();
        }
        return projectId;
    }

    private boolean canAccess(MemoryScopeRef scope, String tenantId, String principalId) {
        if (scope == null || tenantId == null || tenantId.isBlank() || principalId == null || principalId.isBlank()) {
            return false;
        }
        return switch (scope.scope()) {
            case USER -> Objects.equals(scope.scopeId(), principalId);
            case PROJECT -> canAccessProject(scope.scopeId(), tenantId, principalId);
            case TASK -> projectOwnership.findProjectIdByTask(scope.scopeId())
                    .filter(projectId -> canAccessProject(projectId, tenantId, principalId))
                    .isPresent();
        };
    }

    private boolean canAccessProject(String projectId, String tenantId, String principalId) {
        return projectOwnership.findTenantIdByProject(projectId).filter(tenantId::equals).isPresent()
                && projectOwnership.isOwnerOrMember(projectId, principalId);
    }

    private BusinessException notFound() {
        return new BusinessException("Memory not found", HttpStatus.NOT_FOUND);
    }
}
