package com.spaceagent.platform.project.application;

import com.spaceagent.platform.project.api.ProjectDirectoryApplicationApi;
import com.spaceagent.platform.project.domain.Project;
import com.spaceagent.platform.project.domain.ProjectDirectory;
import com.spaceagent.platform.project.domain.ProjectDirectoryRepository;
import com.spaceagent.platform.project.domain.ProjectDirectoryState;
import com.spaceagent.platform.project.domain.ProjectRole;
import com.spaceagent.platform.project.domain.SourceRepository;
import com.spaceagent.platform.project.domain.SourceRepositoryRepository;
import com.spaceagent.platform.project.domain.SourceRepositoryState;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ProjectDirectoryApplicationService implements ProjectDirectoryApplicationApi {
    private final ProjectDirectoryRepository directories;
    private final SourceRepositoryRepository sources;
    private final ProjectAccessPolicy access;
    private final IdGenerator ids;
    private final TimeProvider time;

    public ProjectDirectoryApplicationService(
            ProjectDirectoryRepository directories,
            SourceRepositoryRepository sources,
            ProjectAccessPolicy access,
            IdGenerator ids,
            TimeProvider time) {
        this.directories = directories;
        this.sources = sources;
        this.access = access;
        this.ids = ids;
        this.time = time;
    }

    @Override
    @Transactional
    public DirectoryView create(CreateCommand command) {
        Project project = requireWork(command.tenantId(), command.userId(), command.projectId());
        SourceRepository source = requireSource(project, command.sourceRepositoryId());
        return view(createSourceDirectory(
                project, source, command.userId(), command.name(), command.relativePath()));
    }

    @Override
    @Transactional(readOnly = true)
    public DirectoryView get(Query query) {
        requireView(query.tenantId(), query.userId(), query.projectId());
        return view(require(query.projectId(), query.directoryId(), false));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DirectoryView> list(ListQuery query) {
        requireView(query.tenantId(), query.userId(), query.projectId());
        return directories.findByProjectId(query.projectId()).stream().map(this::view).toList();
    }

    @Override
    @Transactional
    public DirectoryView archive(ArchiveCommand command) {
        Project project=requireWork(command.tenantId(), command.userId(), command.projectId());
        ProjectDirectory current = require(command.projectId(), command.directoryId(), false);
        if(!current.createdBy().equals(command.userId()))access.requireRole(project,command.userId(),ProjectRole::canArchive);
        try {
            ProjectDirectory archived = current.archive(time.now());
            directories.save(archived);
            return view(archived);
        } catch (IllegalStateException error) {
            throw conflict(error.getMessage(), "PROJECT_DIRECTORY_STATE_CONFLICT");
        }
    }

    @Override
    @Transactional
    public DirectoryView ensureDefault(EnsureDefaultCommand command) {
        Project project = requireView(command.tenantId(), command.userId(), command.projectId());
        return view(ensureDefault(project, command.userId()));
    }

    @Override
    @Transactional
    public DirectoryView resolveConversationDirectory(ResolveConversationCommand command) {
        Project project = requireView(command.tenantId(), command.userId(), command.projectId());
        if (command.requestedDirectoryId() == null || command.requestedDirectoryId().isBlank()) {
            return view(ensureDefault(project, command.userId()));
        }
        return view(require(project.id(), command.requestedDirectoryId(), true));
    }

    @Override
    @Transactional
    public DirectoryView resolveWorkspaceDirectory(ResolveWorkspaceCommand command) {
        Project project = requireWork(command.tenantId(), command.userId(), command.projectId());
        SourceRepository source = requireSource(project, command.sourceRepositoryId());
        if (command.requestedDirectoryId() != null && !command.requestedDirectoryId().isBlank()) {
            ProjectDirectory requested = require(project.id(), command.requestedDirectoryId(), true);
            if (!source.id().equals(requested.sourceRepositoryId())) {
                throw conflict(
                        "ProjectDirectory does not belong to the Workspace SourceRepository",
                        "PROJECT_DIRECTORY_SOURCE_MISMATCH");
            }
            if (!".".equals(requested.relativePath())) {
                throw conflict(
                        "Only a SourceRepository root can bind a Sandbox Workspace in M51-PR1",
                        "PROJECT_DIRECTORY_SANDBOX_ROOT_REQUIRED");
            }
            return view(requested);
        }
        ProjectDirectory existing = directories.findBySourceAndPath(project.id(), source.id(), ".")
                .filter(value -> value.state() == ProjectDirectoryState.ACTIVE).orElse(null);
        if (existing != null) return view(existing);
        return view(createSourceDirectory(
                project, source, command.userId(), source.displayName(), "."));
    }

    private ProjectDirectory ensureDefault(Project project, String userId) {
        ProjectDirectory current = directories.findDefault(project.id()).orElse(null);
        if (current != null) {
            if (current.state()==ProjectDirectoryState.ARCHIVED) throw conflict("Default root was deleted; select an active root", "PROJECT_DIRECTORY_ARCHIVED");
            return current;
        }
        Instant now = time.now();
        ProjectDirectory created = ProjectDirectory.defaultFor(ids.nextId(), project, userId, now);
        try {
            directories.save(created);
            return created;
        } catch (DataIntegrityViolationException | IllegalStateException error) {
            return directories.findDefault(project.id()).orElseThrow(() -> error);
        }
    }

    private ProjectDirectory createSourceDirectory(
            Project project, SourceRepository source, String userId,
            String requestedName, String requestedPath) {
        String path = relativePath(requestedPath);
        ProjectDirectory current = directories.findBySourceAndPath(project.id(), source.id(), path)
                .filter(value -> value.state() == ProjectDirectoryState.ACTIVE).orElse(null);
        if (current != null) return current;
        String name = name(requestedName, source.displayName());
        Instant now = time.now();
        ProjectDirectory created = new ProjectDirectory(
                ids.nextId(), project.tenantId(), project.id(), source.id(), name, path,
                false, ProjectDirectoryState.ACTIVE, userId, now, now);
        try {
            directories.save(created);
            return created;
        } catch (DataIntegrityViolationException | IllegalStateException error) {
            return directories.findBySourceAndPath(project.id(), source.id(), path)
                    .filter(value -> value.state() == ProjectDirectoryState.ACTIVE)
                    .orElseThrow(() -> error);
        }
    }

    private Project requireView(String tenantId, String userId, String projectId) {
        Project project = access.requireProject(tenantId, userId, projectId);
        access.requireActive(project);
        return project;
    }

    private Project requireWork(String tenantId, String userId, String projectId) {
        Project project = requireView(tenantId, userId, projectId);
        access.requireRole(project, userId, ProjectRole::canWorkOnTasks);
        return project;
    }

    private SourceRepository requireSource(Project project, String sourceId) {
        requireUuid(sourceId);
        return sources.findById(sourceId)
                .filter(value -> value.projectId().equals(project.id()))
                .filter(value -> value.tenantId().equals(project.tenantId()))
                .filter(value -> value.state() == SourceRepositoryState.READY)
                .orElseThrow(() -> notFound("SourceRepository"));
    }

    private ProjectDirectory require(String projectId, String directoryId, boolean active) {
        requireUuid(directoryId);
        return directories.findById(directoryId)
                .filter(value -> value.projectId().equals(projectId))
                .filter(value -> !active || value.state() == ProjectDirectoryState.ACTIVE)
                .orElseThrow(() -> notFound("ProjectDirectory"));
    }

    private static String relativePath(String value) {
        String path = value == null || value.isBlank() ? "." : value.trim().replace('\\', '/');
        if (path.startsWith("/") || path.endsWith("/") || path.contains("//")
                || path.matches("^[A-Za-z]:/.*") || path.indexOf('\0') >= 0
                || path.length() > 500) {
            throw invalid("ProjectDirectory path must be a bounded relative path");
        }
        if (!path.equals(".")) {
            for (String segment : path.split("/")) {
                if (segment.isBlank() || segment.equals(".") || segment.equals("..")) {
                    throw invalid("ProjectDirectory path cannot contain traversal segments");
                }
            }
        }
        return path;
    }

    private static String name(String value, String fallback) {
        String result = value == null || value.isBlank() ? fallback : value.trim();
        if (result == null || result.isBlank() || result.length() > 120) {
            throw invalid("ProjectDirectory name is required and must not exceed 120 characters");
        }
        return result;
    }

    private static void requireUuid(String value) {
        try {
            UUID.fromString(value);
        } catch (Exception error) {
            throw notFound("ProjectDirectory reference");
        }
    }

    private DirectoryView view(ProjectDirectory value) {
        return new DirectoryView(
                value.id(), value.tenantId(), value.projectId(), value.sourceRepositoryId(),
                value.name(), value.relativePath(), value.defaultDirectory(), value.state(),
                value.createdAt(), value.updatedAt());
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(message, HttpStatus.BAD_REQUEST, "PROJECT_DIRECTORY_INPUT_INVALID");
    }

    private static BusinessException conflict(String message, String code) {
        return new BusinessException(message, HttpStatus.CONFLICT, code);
    }

    private static BusinessException notFound(String noun) {
        return new BusinessException(noun + " not found", HttpStatus.NOT_FOUND,
                "PROJECT_DIRECTORY_NOT_FOUND");
    }
}
