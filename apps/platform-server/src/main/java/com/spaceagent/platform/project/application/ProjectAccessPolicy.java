package com.spaceagent.platform.project.application;

import com.spaceagent.platform.identity.api.IdentityOwnershipPort;
import com.spaceagent.platform.project.domain.Project;
import com.spaceagent.platform.project.domain.ProjectMembership;
import com.spaceagent.platform.project.domain.ProjectMembershipRepository;
import com.spaceagent.platform.project.domain.ProjectRepository;
import com.spaceagent.platform.project.domain.ProjectRole;
import com.spaceagent.platform.project.domain.ProjectStatus;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Predicate;

/** Shared tenant, Project, and Project-role authorization policy for Project-owned use cases. */
@Component
public class ProjectAccessPolicy {

    private final ProjectRepository projectRepository;
    private final ProjectMembershipRepository membershipRepository;
    private final IdentityOwnershipPort identityOwnership;
    private final com.spaceagent.platform.identity.api.IdentityExecutionAuthorizationApi actorAuthorization;

    public ProjectAccessPolicy(
            ProjectRepository projectRepository,
            ProjectMembershipRepository membershipRepository,
            IdentityOwnershipPort identityOwnership,
            com.spaceagent.platform.identity.api.IdentityExecutionAuthorizationApi actorAuthorization) {
        this.projectRepository = projectRepository;
        this.membershipRepository = membershipRepository;
        this.identityOwnership = identityOwnership;
        this.actorAuthorization = java.util.Objects.requireNonNull(actorAuthorization);
    }

    public Project requireProject(String tenantId, String userId, String projectId) {
        requireIdentityMembership(tenantId, userId);
        requireUuid(projectId);
        Project project = projectRepository.findById(projectId)
                .filter(value -> tenantId.equals(value.tenantId()))
                .orElseThrow(ProjectAccessPolicy::projectNotFound);
        requireRole(project, userId, ProjectRole::canView);
        return project;
    }

    public void requireIdentityMembership(String tenantId, String userId) {
        requireText(tenantId, "tenantId");
        requireText(userId, "userId");
        actorAuthorization.requireActiveActor(tenantId, userId, false);
        if (!identityOwnership.isMemberOfTenant(tenantId, userId)) {
            throw new BusinessException(
                    "Active Tenant membership is required",
                    HttpStatus.FORBIDDEN,
                    "TENANT_MEMBERSHIP_REQUIRED");
        }
    }

    public ProjectMembership requireRole(
            Project project,
            String userId,
            Predicate<ProjectRole> permission) {
        requireText(userId, "userId");
        actorAuthorization.requireActiveActor(project.tenantId(), userId, !permission.test(ProjectRole.VIEWER));
        ProjectMembership membership = membershipRepository
                .findMembership(project.id(), userId)
                .orElseThrow(ProjectAccessPolicy::accessDenied);
        if (!permission.test(membership.role())) {
            throw accessDenied();
        }
        return membership;
    }

    public boolean hasRole(
            Project project,
            String userId,
            Predicate<ProjectRole> permission) {
        return membershipRepository.findMembership(project.id(), userId)
                .map(ProjectMembership::role)
                .filter(permission)
                .isPresent();
    }

    public void requireActive(Project project) {
        if (project.status() != ProjectStatus.ACTIVE) {
            throw new BusinessException(
                    "Archived Project cannot be modified",
                    HttpStatus.CONFLICT,
                    "PROJECT_ARCHIVED");
        }
    }

    private static void requireUuid(String value) {
        requireText(value, "projectId");
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw projectNotFound();
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(
                    field + " is required", HttpStatus.BAD_REQUEST, "PROJECT_INPUT_INVALID");
        }
        return value.trim();
    }

    private static BusinessException projectNotFound() {
        return new BusinessException(
                "Project not found", HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND");
    }

    private static BusinessException accessDenied() {
        return new BusinessException(
                "Project access denied", HttpStatus.FORBIDDEN, "PROJECT_ACCESS_DENIED");
    }
}
