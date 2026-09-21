package com.spaceagent.platform.project.application;

import com.spaceagent.platform.project.api.*;
import com.spaceagent.platform.project.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;

@Service
public class ProjectRootApplicationService implements ProjectRootApplicationApi {
    private final ProjectApplicationApi projects;
    private final ProjectRepository projectRepository;
    private final ProjectDirectoryRepository directories;
    private final ProjectDirectoryApplicationApi directoryApi;
    private final SourceRepositoryRepository sources;
    private final WorkspaceProvisioningGateway storage;
    private final ProjectAccessPolicy access;
    private final IdGenerator ids;
    private final TimeProvider time;
    private final TaskApplicationApi tasks;
    private final WorkspaceApplicationApi workspaces;
    public ProjectRootApplicationService(ProjectApplicationApi projects,ProjectRepository projectRepository,
            ProjectDirectoryRepository directories,ProjectDirectoryApplicationApi directoryApi,SourceRepositoryRepository sources,
            WorkspaceProvisioningGateway storage,ProjectAccessPolicy access,IdGenerator ids,TimeProvider time,
            TaskApplicationApi tasks,WorkspaceApplicationApi workspaces) {
        this.projects=projects;this.projectRepository=projectRepository;this.directories=directories;this.directoryApi=directoryApi;
        this.sources=sources;this.storage=storage;this.access=access;this.ids=ids;this.time=time;
        this.tasks=tasks;this.workspaces=workspaces;
    }
    @Transactional(readOnly=true)
    public List<RootView> list(String tenantId,String userId) {
        access.requireIdentityMembership(tenantId,userId);
        List<RootView> result=new ArrayList<>();
        for(var project:projectRepository.findByTenantId(tenantId)) {
            if(project.status()!=ProjectStatus.ACTIVE || !access.hasRole(project,userId,ProjectRole::canView)) continue;
            directories.findByProjectId(project.id()).stream().filter(directory -> directory.state()==ProjectDirectoryState.ACTIVE)
                    .map(this::view).forEach(result::add);
        }
        return result.stream().limit(500).toList();
    }
    @Transactional
    public synchronized RootView create(CreateCommand command) {
        if(command.name()==null || command.name().isBlank() || command.name().trim().length()>120)
            throw new BusinessException("Root name is required (maximum 120 characters)",HttpStatus.BAD_REQUEST,"PROJECT_ROOT_NAME_INVALID");
        String projectId=command.projectId();
        boolean newProject=projectId==null || projectId.isBlank();
        if(newProject) projectId=projects.createProject(new CreateProjectCommand(command.tenantId(),command.userId(),command.name().trim(),null)).id();
        var project=access.requireProject(command.tenantId(),command.userId(),projectId);
        access.requireActive(project);access.requireRole(project,command.userId(),ProjectRole::canWorkOnTasks);
        if(command.sourceRepositoryId()!=null && !command.sourceRepositoryId().isBlank()) {
            var created=directoryApi.create(new ProjectDirectoryApplicationApi.CreateCommand(command.tenantId(),command.userId(),projectId,command.sourceRepositoryId(),command.name().trim(),"."));
            return prepare(command.tenantId(),command.userId(),created.id());
        }
        ProjectDirectory directory;
        if(newProject) directory=directories.findDefault(projectId).orElseThrow();
        else {
            var now=time.now();String id=ids.nextId();
            directory=new ProjectDirectory(id,command.tenantId(),projectId,id,command.name().trim(),".",false,ProjectDirectoryState.ACTIVE,command.userId(),now,now);
            sources.save(emptySource(directory));directories.save(directory);
        }
        return prepare(command.tenantId(),command.userId(),directory.id());
    }
    @Transactional
    public synchronized RootView prepare(String tenantId,String userId,String rootId) {
        var directory=directories.findByIdForUpdate(rootId).orElseThrow(ProjectRootApplicationService::missing);
        var project=access.requireProject(tenantId,userId,directory.projectId());
        access.requireActive(project);access.requireRole(project,userId,ProjectRole::canWorkOnTasks);
        if(directory.state()!=ProjectDirectoryState.ACTIVE) throw missing();
        if(directory.sourceRepositoryId()==null) {
            sources.save(emptySource(directory));
            directory=directory.bindManagedSource(directory.id(),time.now());directories.save(directory);
        }
        var source=sources.findById(directory.sourceRepositoryId()).orElseThrow();
        if(source.state()==SourceRepositoryState.ARCHIVED)throw new BusinessException("Root source is archived",HttpStatus.CONFLICT,"PROJECT_ROOT_SOURCE_ARCHIVED");
        if(source.type()==SourceRepositoryType.GENERIC && source.remoteUrl()==null) {
            storage.initializeEmptySource(source);
            if(source.state()!=SourceRepositoryState.READY) sources.save(source.ready(time.now()));
        } else if(source.remoteUrl()!=null && storage.branches(source.id()).isEmpty()) {
            var task=tasks.createTask(new CreateTaskCommand(tenantId,userId,project.id(),null,
                    "Root storage: "+directory.name(),"Prepare repository root storage",null,List.of(),List.of()));
            workspaces.provision(new WorkspaceApplicationApi.ProvisionCommand(tenantId,userId,project.id(),directory.id(),
                    task.id(),source.id(),source.defaultBranch(),"root-storage"));
        }
        return view(directory);
    }
    @Transactional
    public RootView archive(String tenantId,String userId,String rootId) {
        var directory=directories.findByIdForUpdate(rootId).orElseThrow(ProjectRootApplicationService::missing);
        var project=access.requireProject(tenantId,userId,directory.projectId());
        if(!directory.createdBy().equals(userId)) access.requireRole(project,userId,ProjectRole::canArchive);
        var archived=directory.archive(time.now());directories.save(archived);
        return view(archived);
    }
    @Transactional
    public RootView rename(String tenantId,String userId,String rootId,String name) {
        var directory=directories.findByIdForUpdate(rootId).orElseThrow(ProjectRootApplicationService::missing);
        var project=access.requireProject(tenantId,userId,directory.projectId());access.requireActive(project);
        if(!directory.createdBy().equals(userId)) access.requireRole(project,userId,ProjectRole::canModify);
        if(directory.state()!=ProjectDirectoryState.ACTIVE)throw missing();
        if(name==null || name.isBlank() || name.trim().length()>120)throw new BusinessException("Invalid root name",HttpStatus.BAD_REQUEST,"PROJECT_ROOT_NAME_INVALID");
        var updated=new ProjectDirectory(directory.id(),directory.tenantId(),directory.projectId(),directory.sourceRepositoryId(),name.trim(),directory.relativePath(),directory.defaultDirectory(),directory.state(),directory.createdBy(),directory.createdAt(),time.now());
        directories.save(updated);return view(updated);
    }
    private SourceRepository emptySource(ProjectDirectory directory) {
        return new SourceRepository(directory.id(),directory.projectId(),directory.tenantId(),null,null,null,
                "managed:"+directory.id(),directory.name(),null,null,"main",SourceRepositoryType.GENERIC,
                SourceRepositoryState.PROVISIONING,SourceRepositoryVisibility.INTERNAL,directory.createdBy(),time.now(),time.now());
    }
    private RootView view(ProjectDirectory directory) {
        var source=directory.sourceRepositoryId()==null?null:sources.findById(directory.sourceRepositoryId()).orElse(null);
        boolean empty=source==null || source.type()==SourceRepositoryType.GENERIC && source.remoteUrl()==null;
        return new RootView(directory.id(),directory.projectId(),directory.sourceRepositoryId(),directory.name(),directory.relativePath(),
                directory.defaultDirectory(),directory.state().name(),empty?"EMPTY":"REPOSITORY",source==null?"UNINITIALIZED":source.state().name(),
                source==null?null:"repositories/"+source.id()+".git",directory.tenantId(),directory.createdAt(),directory.updatedAt());
    }
    private static BusinessException missing(){return new BusinessException("Active project root not found",HttpStatus.NOT_FOUND,"PROJECT_ROOT_NOT_FOUND");}
}
