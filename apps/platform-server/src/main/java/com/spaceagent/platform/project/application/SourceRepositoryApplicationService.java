package com.spaceagent.platform.project.application;

import com.spaceagent.platform.project.api.*;
import com.spaceagent.platform.project.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class SourceRepositoryApplicationService implements SourceRepositoryApplicationApi {

    private final SourceRepositoryRepository repository;
    private final ProjectAccessPolicy accessPolicy;
    private final LocalWorkspaceBridgeApplicationApi bridges;
    private final ProjectDirectoryRepository directories;
    private final IdGenerator ids;
    private final TimeProvider time;

    public SourceRepositoryApplicationService(
            SourceRepositoryRepository repository,
            ProjectAccessPolicy accessPolicy,
            LocalWorkspaceBridgeApplicationApi bridges,
            IdGenerator ids,
            TimeProvider time) {
        this(repository, accessPolicy, bridges, ids, time, null);
    }

    @Autowired
    public SourceRepositoryApplicationService(
            SourceRepositoryRepository repository,
            ProjectAccessPolicy accessPolicy,
            LocalWorkspaceBridgeApplicationApi bridges,
            IdGenerator ids,
            TimeProvider time,
            ProjectDirectoryRepository directories) {
        this.repository = repository;
        this.accessPolicy = accessPolicy;
        this.bridges = bridges;
        this.directories = directories;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public SourceRepositoryView importLocal(ImportLocalRepositoryCommand command) {
        Project project = requireManageableProject(
                command.tenantId(), command.userId(), command.projectId());
        LocalWorkspaceBridgeView bridge = bridges.resolve(new ResolveLocalWorkspaceBridgeQuery(
                command.tenantId(), command.userId(), command.bridgeId(), command.rootHandle()));
        if (repository.existsLocal(command.projectId(), bridge.id(), bridge.rootHandle())) {
            throw duplicate();
        }
        String displayName = bounded(command.displayName(), "displayName", 200);
        String branch = normalize(command.defaultBranch());
        if (branch == null) branch = "main";
        Instant now = time.now();
        SourceRepository source = new SourceRepository(
                ids.nextId(), command.projectId(), command.tenantId(), null, null, bridge.id(),
                "local:" + bridge.id() + ":" + bridge.rootHandle(), displayName,
                null, bridge.rootHandle(), branch, SourceRepositoryType.LOCAL,
                SourceRepositoryState.READY, SourceRepositoryVisibility.LOCAL,
                command.userId(), now, now);
        save(source);
        ensureSourceRoot(project, source, command.userId(), now);
        return toView(source);
    }

    @Override
    public SourceRepositoryView importGithubMcp(ImportGithubMcpRepositoryCommand command) {
        Project project = requireManageableProject(
                command.tenantId(), command.userId(), command.projectId());
        if (command.archived()) {
            throw new BusinessException(
                    "Archived GitHub repository cannot be imported",
                    HttpStatus.CONFLICT,
                    "SOURCE_REPOSITORY_REMOTE_ARCHIVED");
        }
        String providerId = bounded(command.providerRepositoryId(), "providerRepositoryId", 180);
        SourceRepository existing = repository.findActiveGithub(command.projectId(), providerId)
                .orElse(null);
        if (existing != null) {
            ensureSourceRoot(project, existing, command.userId(), time.now());
            return toView(existing);
        }
        String connection = uuid(command.mcpConnectionId(), "mcpConnectionId");
        String invocation = uuid(command.mcpInvocationId(), "mcpInvocationId");
        String owner = githubIdentifier(command.owner(), "owner");
        String name = githubIdentifier(command.repository(), "repository");
        String cloneUrl = githubCloneUrl(command.cloneUrl(), owner, name);
        String branch = bounded(command.defaultBranch(), "defaultBranch", 255);
        Instant now = time.now();
        SourceRepository source = new SourceRepository(
                ids.nextId(), command.projectId(), command.tenantId(), connection, invocation, null,
                providerId, owner + "/" + name, cloneUrl, null, branch,
                SourceRepositoryType.GITHUB, SourceRepositoryState.READY,
                command.privateRepository()
                        ? SourceRepositoryVisibility.PRIVATE : SourceRepositoryVisibility.PUBLIC,
                command.userId(), now, now);
        save(source);
        ensureSourceRoot(project, source, command.userId(), now);
        return toView(source);
    }

    @Override
    public void requireImportAccess(RequireSourceImportAccessCommand command) {
        requireManageableProject(command.tenantId(), command.userId(), command.projectId());
    }

    @Override
    @Transactional(readOnly = true)
    public SourceRepositoryView get(GetSourceRepositoryQuery query) {
        requireProject(query.tenantId(), query.userId(), query.projectId());
        return toView(requireSource(query.projectId(), query.sourceRepositoryId()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SourceRepositoryView> list(ListSourceRepositoriesQuery query) {
        requireProject(query.tenantId(), query.userId(), query.projectId());
        if(query.offset()<0||query.offset()>100_000||query.limit()<1||query.limit()>100)
            throw new BusinessException("Invalid Source page bounds",HttpStatus.BAD_REQUEST);
        return repository.pageByProjectId(query.projectId(),query.offset(),query.limit()).stream()
                .map(SourceRepositoryApplicationService::toView)
                .toList();
    }

    @Override
    public SourceRepositoryView archive(ArchiveSourceRepositoryCommand command) {
        requireManageableProject(command.tenantId(), command.userId(), command.projectId());
        SourceRepository current = requireSource(
                command.projectId(), command.sourceRepositoryId());
        if (current.state() == SourceRepositoryState.ARCHIVED) return toView(current);
        SourceRepository archived = current.archive(time.now());
        repository.save(archived);
        return toView(archived);
    }

    private Project requireProject(String tenantId, String userId, String projectId) {
        return accessPolicy.requireProject(tenantId, userId, projectId);
    }

    private Project requireManageableProject(String tenantId, String userId, String projectId) {
        Project project = requireProject(tenantId, userId, projectId);
        accessPolicy.requireActive(project);
        accessPolicy.requireRole(project, userId, ProjectRole::canModify);
        return project;
    }

    private SourceRepository requireSource(String projectId, String sourceId) {
        try { UUID.fromString(sourceId); } catch (Exception error) { throw notFound(); }
        return repository.findById(sourceId)
                .filter(value -> projectId.equals(value.projectId()))
                .orElseThrow(SourceRepositoryApplicationService::notFound);
    }

    private void save(SourceRepository source) {
        try {
            repository.save(source);
        } catch (DataIntegrityViolationException error) {
            throw duplicate();
        }
    }

    private void ensureSourceRoot(
            Project project, SourceRepository source, String userId, Instant now) {
        if (directories == null) return;
        ProjectDirectory current = directories.findBySourceAndPath(project.id(), source.id(), ".")
                .filter(value -> value.state() == ProjectDirectoryState.ACTIVE)
                .orElse(null);
        if (current == null) {
            directories.save(ProjectDirectory.sourceRootFor(
                    ids.nextId(), project, source, userId, now));
        }
    }

    private static SourceRepositoryView toView(SourceRepository source) {
        return new SourceRepositoryView(
                source.id(), source.projectId(), source.mcpConnectionId(),
                source.mcpInvocationId(), source.workspaceBridgeId(),
                source.providerRepositoryId(), source.displayName(),
                source.remoteUrl(), source.localRootHandle(), source.defaultBranch(),
                source.type(), source.state(), source.visibility(), source.createdBy(),
                source.createdAt(), source.updatedAt(), source.materializationSessionId(),
                source.snapshotRef(), source.manifestSha256(), source.contentSha256(),
                source.finalizeRequestId());
    }

    private static String bounded(String value, String field, int max) {
        String normalized = normalize(value);
        if (normalized == null || normalized.length() > max) {
            throw new BusinessException(
                    field + " is invalid", HttpStatus.BAD_REQUEST, "SOURCE_REPOSITORY_INPUT_INVALID");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String uuid(String value, String field) {
        try {
            return UUID.fromString(value).toString();
        } catch (Exception error) {
            throw new BusinessException(
                    field + " is invalid", HttpStatus.BAD_REQUEST,
                    "SOURCE_REPOSITORY_INPUT_INVALID");
        }
    }

    private static String githubIdentifier(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null || normalized.length() > 100
                || !normalized.matches("[A-Za-z0-9_.-]+")) {
            throw new BusinessException(
                    field + " is invalid", HttpStatus.BAD_REQUEST,
                    "SOURCE_REPOSITORY_INPUT_INVALID");
        }
        return normalized;
    }

    private static String githubCloneUrl(String value, String owner, String repository) {
        String expected = "https://github.com/" + owner + "/" + repository + ".git";
        if (!expected.equalsIgnoreCase(normalize(value))) {
            throw new BusinessException(
                    "cloneUrl is invalid", HttpStatus.BAD_REQUEST,
                    "SOURCE_REPOSITORY_INPUT_INVALID");
        }
        return expected;
    }

    private static BusinessException duplicate() {
        return new BusinessException(
                "Source repository is already imported into this Project",
                HttpStatus.CONFLICT,
                "SOURCE_REPOSITORY_CONFLICT");
    }

    private static BusinessException notFound() {
        return new BusinessException(
                "Source repository not found", HttpStatus.NOT_FOUND,
                "SOURCE_REPOSITORY_NOT_FOUND");
    }
}
