package com.spaceagent.platform.project.application;

import com.spaceagent.platform.project.api.AddProjectMemberCommand;
import com.spaceagent.platform.project.api.ArchiveProjectCommand;
import com.spaceagent.platform.project.api.CreateProjectCommand;
import com.spaceagent.platform.project.api.GetProjectQuery;
import com.spaceagent.platform.project.api.ListProjectMembersQuery;
import com.spaceagent.platform.project.api.ListProjectsQuery;
import com.spaceagent.platform.project.api.ProjectApplicationApi;
import com.spaceagent.platform.project.api.ProjectMembershipView;
import com.spaceagent.platform.project.api.ProjectView;
import com.spaceagent.platform.project.api.RemoveProjectMemberCommand;
import com.spaceagent.platform.project.api.UpdateProjectCommand;
import com.spaceagent.platform.project.domain.Project;
import com.spaceagent.platform.project.domain.ProjectMembership;
import com.spaceagent.platform.project.domain.ProjectMembershipRepository;
import com.spaceagent.platform.project.domain.ProjectDirectory;
import com.spaceagent.platform.project.domain.ProjectDirectoryRepository;
import com.spaceagent.platform.project.domain.ProjectRepository;
import com.spaceagent.platform.project.domain.ProjectRole;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.List;

/** Application coordinator for tenant-scoped Project lifecycle and authorization. */
@Service
public class ProjectApplicationService implements ProjectApplicationApi {

    private final ProjectRepository projectRepository;
    private final ProjectMembershipRepository membershipRepository;
    private final ProjectAccessPolicy accessPolicy;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;
    private final ProjectDirectoryRepository directoryRepository;

    public ProjectApplicationService(
            ProjectRepository projectRepository,
            ProjectMembershipRepository membershipRepository,
            ProjectAccessPolicy accessPolicy,
            IdGenerator idGenerator,
            TimeProvider timeProvider) {
        this(projectRepository, membershipRepository, accessPolicy, idGenerator, timeProvider, null);
    }

    @Autowired
    public ProjectApplicationService(
            ProjectRepository projectRepository,
            ProjectMembershipRepository membershipRepository,
            ProjectAccessPolicy accessPolicy,
            IdGenerator idGenerator,
            TimeProvider timeProvider,
            ProjectDirectoryRepository directoryRepository) {
        this.projectRepository = projectRepository;
        this.membershipRepository = membershipRepository;
        this.accessPolicy = accessPolicy;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
        this.directoryRepository = directoryRepository;
    }

    @Override
    @Transactional
    public ProjectView createProject(CreateProjectCommand command) {
        accessPolicy.requireIdentityMembership(command.tenantId(), command.userId());
        String name = normalizeName(command.name());
        rejectDuplicateName(command.tenantId(), name, null);

        Instant now = timeProvider.now();
        Project project = Project.create(
                idGenerator.nextId(), command.tenantId(), command.userId(),
                name, command.description(), now);
        saveProject(project);
        membershipRepository.addMember(ProjectMembership.create(
                idGenerator.nextId(), project.id(), command.userId(), ProjectRole.OWNER, now));
        if (directoryRepository != null) {
            directoryRepository.save(ProjectDirectory.defaultFor(
                    idGenerator.nextId(), project, command.userId(), now));
        }
        return toView(project);
    }

    @Override
    public ProjectView getProject(GetProjectQuery query) {
        Project project = accessPolicy.requireProject(
                query.tenantId(), query.userId(), query.projectId());
        return toView(project);
    }

    @Override
    public List<ProjectView> listProjects(ListProjectsQuery query) {
        accessPolicy.requireIdentityMembership(query.tenantId(), query.userId());
        return projectRepository.findByTenantId(query.tenantId()).stream()
                .filter(project -> accessPolicy.hasRole(project, query.userId(), ProjectRole::canView))
                .map(ProjectApplicationService::toView)
                .toList();
    }

    @Override
    @Transactional
    public ProjectView updateProject(UpdateProjectCommand command) {
        Project current = accessPolicy.requireProject(
                command.tenantId(), command.userId(), command.projectId());
        accessPolicy.requireActive(current);
        accessPolicy.requireRole(current, command.userId(), ProjectRole::canModify);

        Project updated = current;
        Instant now = timeProvider.now();
        if (command.name() != null) {
            String name = normalizeName(command.name());
            rejectDuplicateName(current.tenantId(), name, current.id());
            updated = updated.rename(name, now);
        }
        if (command.descriptionPresent()) {
            updated = updated.updateDescription(command.description(), now);
        }
        saveProject(updated);
        return toView(updated);
    }

    @Override
    @Transactional
    public ProjectView archiveProject(ArchiveProjectCommand command) {
        Project current = accessPolicy.requireProject(
                command.tenantId(), command.userId(), command.projectId());
        accessPolicy.requireRole(current, command.userId(), ProjectRole::canArchive);
        Project archived = current.archive(timeProvider.now());
        saveProject(archived);
        return toView(archived);
    }

    @Override
    @Transactional
    public ProjectMembershipView addMember(AddProjectMemberCommand command) {
        Project project = accessPolicy.requireProject(
                command.tenantId(), command.userId(), command.projectId());
        accessPolicy.requireActive(project);
        accessPolicy.requireRole(project, command.userId(), ProjectRole::canManageMembers);
        accessPolicy.requireIdentityMembership(project.tenantId(), command.memberUserId());
        ProjectRole role = requireRoleValue(command.role());

        if (role == ProjectRole.OWNER && !project.ownerId().equals(command.memberUserId())) {
            throw new BusinessException(
                    "Project ownership cannot be delegated through membership",
                    HttpStatus.CONFLICT,
                    "PROJECT_OWNER_IMMUTABLE");
        }
        if (project.ownerId().equals(command.memberUserId()) && role != ProjectRole.OWNER) {
            throw new BusinessException(
                    "Project owner role cannot be changed",
                    HttpStatus.CONFLICT,
                    "PROJECT_OWNER_IMMUTABLE");
        }

        ProjectMembership membership = membershipRepository
                .findMembership(project.id(), command.memberUserId())
                .map(existing -> existing.changeRole(role))
                .orElseGet(() -> ProjectMembership.create(
                        idGenerator.nextId(), project.id(), command.memberUserId(),
                        role, timeProvider.now()));
        membershipRepository.addMember(membership);
        return toView(membership);
    }

    @Override
    @Transactional
    public void removeMember(RemoveProjectMemberCommand command) {
        Project project = accessPolicy.requireProject(
                command.tenantId(), command.userId(), command.projectId());
        accessPolicy.requireActive(project);
        accessPolicy.requireRole(project, command.userId(), ProjectRole::canManageMembers);
        if (project.ownerId().equals(command.memberUserId())) {
            throw new BusinessException(
                    "Project owner cannot be removed",
                    HttpStatus.CONFLICT,
                    "PROJECT_OWNER_IMMUTABLE");
        }
        if (!membershipRepository.removeMember(project.id(), command.memberUserId())) {
            throw membershipNotFound();
        }
    }

    @Override
    public List<ProjectMembershipView> listMembers(ListProjectMembersQuery query) {
        Project project = accessPolicy.requireProject(
                query.tenantId(), query.userId(), query.projectId());
        accessPolicy.requireRole(project, query.userId(), ProjectRole::canManageMembers);
        return membershipRepository.listMembers(project.id()).stream()
                .map(ProjectApplicationService::toView)
                .toList();
    }

    private void rejectDuplicateName(String tenantId, String name, String currentProjectId) {
        if (!projectRepository.exists(tenantId, name)) {
            return;
        }
        boolean sameProject = currentProjectId != null
                && projectRepository.findById(currentProjectId)
                .filter(project -> project.tenantId().equals(tenantId))
                .filter(project -> project.name().equals(name))
                .isPresent();
        if (!sameProject) {
            throw duplicateName();
        }
    }

    private void saveProject(Project project) {
        try {
            projectRepository.save(project);
        } catch (DataIntegrityViolationException exception) {
            throw duplicateName();
        }
    }

    private static ProjectRole requireRoleValue(ProjectRole role) {
        if (role == null) {
            throw new BusinessException(
                    "Project role is required", HttpStatus.BAD_REQUEST, "PROJECT_ROLE_REQUIRED");
        }
        return role;
    }

    private static String normalizeName(String value) {
        String name = requireText(value, "name");
        if (name.length() > 128) {
            throw new BusinessException(
                    "Project name must not exceed 128 characters",
                    HttpStatus.BAD_REQUEST,
                    "PROJECT_NAME_INVALID");
        }
        return name;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(
                    field + " is required", HttpStatus.BAD_REQUEST, "PROJECT_INPUT_INVALID");
        }
        return value.trim();
    }

    private static ProjectView toView(Project project) {
        return new ProjectView(
                project.id(), project.tenantId(), project.ownerId(), project.name(),
                project.description(), project.status(), project.createdAt(), project.updatedAt());
    }

    private static ProjectMembershipView toView(ProjectMembership membership) {
        return new ProjectMembershipView(
                membership.id(), membership.projectId(), membership.userId(),
                membership.role(), membership.createdAt());
    }

    private static BusinessException membershipNotFound() {
        return new BusinessException(
                "Project membership not found",
                HttpStatus.NOT_FOUND,
                "PROJECT_MEMBERSHIP_NOT_FOUND");
    }

    private static BusinessException duplicateName() {
        return new BusinessException(
                "Project name already exists in Tenant",
                HttpStatus.CONFLICT,
                "PROJECT_NAME_CONFLICT");
    }
}
