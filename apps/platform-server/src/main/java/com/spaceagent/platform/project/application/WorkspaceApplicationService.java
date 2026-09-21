package com.spaceagent.platform.project.application;

import com.spaceagent.platform.project.api.*;
import com.spaceagent.platform.project.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant; import java.util.List; import java.util.UUID;

@Service
public class WorkspaceApplicationService implements WorkspaceApplicationApi {
    private final WorkspaceRepository workspaces; private final BridgeWorkspaceCommandRepository commands;
    private final ProjectAccessPolicy access; private final TaskRepository tasks;
    private final SourceRepositoryRepository sources; private final LocalWorkspaceBridgeRepository bridgeRepository;
    private final LocalWorkspaceBridgeApplicationApi bridges;
    private final WorkspaceCheckoutCredentialPort checkoutCredentials;
    private final WorkspaceProvisioningGateway gateway;
    private final ManagedSnapshotWorkspaceProvisioningGateway snapshotGateway;
    private final ProjectDirectoryApplicationApi directories;
    private final IdGenerator ids; private final TimeProvider time;
    public WorkspaceApplicationService(WorkspaceRepository workspaces,BridgeWorkspaceCommandRepository commands,
            ProjectAccessPolicy access,TaskRepository tasks,SourceRepositoryRepository sources,
            LocalWorkspaceBridgeRepository bridgeRepository,LocalWorkspaceBridgeApplicationApi bridges,
            WorkspaceCheckoutCredentialPort checkoutCredentials,
            WorkspaceProvisioningGateway gateway,IdGenerator ids,TimeProvider time){
        this(workspaces,commands,access,tasks,sources,bridgeRepository,bridges,
                checkoutCredentials,gateway,ids,time,null,null);
    }
    public WorkspaceApplicationService(WorkspaceRepository workspaces,BridgeWorkspaceCommandRepository commands,
            ProjectAccessPolicy access,TaskRepository tasks,SourceRepositoryRepository sources,
            LocalWorkspaceBridgeRepository bridgeRepository,LocalWorkspaceBridgeApplicationApi bridges,
            WorkspaceCheckoutCredentialPort checkoutCredentials,WorkspaceProvisioningGateway gateway,
            IdGenerator ids,TimeProvider time,ProjectDirectoryApplicationApi directories){
        this(workspaces,commands,access,tasks,sources,bridgeRepository,bridges,checkoutCredentials,gateway,
                ids,time,directories,null);
    }

    @Autowired
    public WorkspaceApplicationService(WorkspaceRepository workspaces,BridgeWorkspaceCommandRepository commands,
            ProjectAccessPolicy access,TaskRepository tasks,SourceRepositoryRepository sources,
            LocalWorkspaceBridgeRepository bridgeRepository,LocalWorkspaceBridgeApplicationApi bridges,
            WorkspaceCheckoutCredentialPort checkoutCredentials,
            WorkspaceProvisioningGateway gateway,IdGenerator ids,TimeProvider time,
            ProjectDirectoryApplicationApi directories,
            ManagedSnapshotWorkspaceProvisioningGateway snapshotGateway){
        this.workspaces=workspaces;this.commands=commands;this.access=access;this.tasks=tasks;this.sources=sources;
        this.bridgeRepository=bridgeRepository;this.bridges=bridges;
        this.checkoutCredentials=checkoutCredentials;this.gateway=gateway;this.ids=ids;this.time=time;
        this.directories=directories;
        this.snapshotGateway=snapshotGateway;
    }
    public WorkspaceView provision(ProvisionCommand c){
        Project p=requireWork(c.tenantId(),c.userId(),c.projectId());
        Task task=tasks.findById(c.taskId()).filter(v->v.projectId().equals(p.id())).orElseThrow(()->notFound("Task"));
        if(task.state().isTerminal()) throw new BusinessException("Terminal Task cannot receive Workspace",HttpStatus.CONFLICT,"WORKSPACE_TASK_TERMINAL");
        SourceRepository source=sources.findById(c.sourceRepositoryId()).filter(v->v.projectId().equals(p.id()))
                .filter(v->v.tenantId().equals(c.tenantId())).filter(v->v.state()==SourceRepositoryState.READY)
                .orElseThrow(()->notFound("Source repository"));
        String directoryId=directories==null?c.projectDirectoryId():directories.resolveWorkspaceDirectory(
                new ProjectDirectoryApplicationApi.ResolveWorkspaceCommand(c.tenantId(),c.userId(),
                        p.id(),c.projectDirectoryId(),source.id())).id();
        String isolationKey=normalizeIsolationKey(c.isolationKey());
        if(workspaces.hasActiveWritable(task.id(),source.id(),isolationKey)) throw new BusinessException("Task already has active writable Workspace for this isolation key",HttpStatus.CONFLICT,"WORKSPACE_ACTIVE_CONFLICT");
        Instant now=time.now();String id=ids.nextId();String key=UUID.randomUUID().toString();
        String base=c.baseRef()==null||c.baseRef().isBlank()?source.defaultBranch():c.baseRef().trim();
        String branch="spaceagent/"+task.id().substring(0,8)+"/"+id.substring(0,8);
        boolean local=source.type()==SourceRepositoryType.LOCAL;
        Workspace w=new Workspace(id,c.tenantId(),p.id(),directoryId,task.id(),source.id(),source.workspaceBridgeId(),isolationKey,
                local?WorkspaceMode.LOCAL_BRIDGE:WorkspaceMode.MANAGED_GIT,key,base,branch,null,null,true,
                local?WorkspaceState.WAITING_FOR_BRIDGE:WorkspaceState.PROVISIONING,null,0,c.userId(),now,now);
        workspaces.save(w);
        if(local){
            BridgeWorkspaceCommand cmd=new BridgeWorkspaceCommand(ids.nextId(),w.id(),source.workspaceBridgeId(),
                    source.localRootHandle(),base,branch,key,BridgeWorkspaceCommandState.PENDING,now,null);
            commands.save(cmd); return view(w);
        }
        try(WorkspaceCheckoutCredentialPort.CredentialLease credential=credential(c,w,source)){
            WorkspaceProvisioningGateway.ProvisionedWorkspace result=source.type()==SourceRepositoryType.MANAGED_SNAPSHOT
                    ? requireSnapshotGateway().provision(w,source)
                    : gateway.provision(w,source,credential.authorizationHeader());
            Workspace ready=w.ready(result.locator(),result.headCommit(),time.now());workspaces.save(ready);return view(ready);
        }catch(RuntimeException e){String code=e instanceof BusinessException b&&b.getCode()!=null?b.getCode():"WORKSPACE_PROVISIONING_FAILED";Workspace failed=w.fail(code,time.now());workspaces.save(failed);if(e instanceof BusinessException b)throw b;throw new BusinessException("Workspace provisioning failed",HttpStatus.BAD_GATEWAY,"WORKSPACE_PROVISIONING_FAILED");}
    }
    private ManagedSnapshotWorkspaceProvisioningGateway requireSnapshotGateway(){if(snapshotGateway==null)throw new BusinessException("Managed snapshot Sandbox is unavailable",HttpStatus.SERVICE_UNAVAILABLE,"MANAGED_SNAPSHOT_SANDBOX_UNAVAILABLE");return snapshotGateway;}
    @Transactional(readOnly=true) public WorkspaceView get(Query q){requireView(q.tenantId(),q.userId(),q.projectId());return view(require(q.projectId(),q.workspaceId()));}
    @Transactional(readOnly=true) public List<WorkspaceView> list(ListQuery q){requireView(q.tenantId(),q.userId(),q.projectId());return workspaces.findByProjectId(q.projectId()).stream().map(WorkspaceApplicationService::view).toList();}
    @Transactional(readOnly=true) public List<BridgeCommandView> pendingBridgeCommands(BridgeCommandsQuery q){
        access.requireIdentityMembership(q.tenantId(),q.userId());
        LocalWorkspaceBridge bridge=bridgeRepository.findById(q.bridgeId()).filter(v->v.tenantId().equals(q.tenantId())).filter(v->v.ownerId().equals(q.userId())).orElseThrow(()->notFound("Bridge"));
        return commands.findPendingByBridgeId(bridge.id()).stream().map(WorkspaceApplicationService::commandView).toList();
    }
    @Transactional public WorkspaceView completeBridge(CompleteBridgeCommand c){
        BridgeWorkspaceCommand cmd=commands.findById(c.commandId()).orElseThrow(()->notFound("Bridge command"));
        Workspace w=workspaces.findById(cmd.workspaceId()).orElseThrow(()->notFound("Workspace"));
        bridges.heartbeat(new HeartbeatLocalWorkspaceBridgeCommand(c.tenantId(),c.userId(),cmd.bridgeId(),c.bridgeToken()));
        if(w.state()!=WorkspaceState.WAITING_FOR_BRIDGE) throw new BusinessException("Workspace is not waiting for Bridge",HttpStatus.CONFLICT,"WORKSPACE_STATE_CONFLICT");
        Workspace updated;
        if(c.success()){
            if(c.opaqueLocator()==null||!c.opaqueLocator().matches("[A-Za-z0-9_-]{8,160}")||c.headCommit()==null||!c.headCommit().matches("[0-9a-fA-F]{7,64}"))
                throw new BusinessException("Invalid Bridge completion evidence",HttpStatus.BAD_REQUEST,"WORKSPACE_BRIDGE_EVIDENCE_INVALID");
            updated=w.ready(c.opaqueLocator(),c.headCommit(),time.now());commands.complete(cmd.id(),BridgeWorkspaceCommandState.COMPLETED,time.now());
        }else{updated=w.fail(c.failureReason()==null?"Bridge provisioning failed":c.failureReason(),time.now());commands.complete(cmd.id(),BridgeWorkspaceCommandState.FAILED,time.now());}
        workspaces.save(updated);return view(updated);
    }
    public WorkspaceView archive(ArchiveCommand c){requireWork(c.tenantId(),c.userId(),c.projectId());Workspace w=require(c.projectId(),c.workspaceId());
        // Tenant archival removes the logical workspace entry, never its stored code.
        Workspace a=w.archive(time.now());workspaces.save(a);return view(a);}
    private Project requireWork(String t,String u,String p){Project v=access.requireProject(t,u,p);access.requireActive(v);access.requireRole(v,u,ProjectRole::canWorkOnTasks);return v;}
    private void requireView(String t,String u,String p){access.requireProject(t,u,p);}
    private Workspace require(String project,String id){try{UUID.fromString(id);}catch(Exception e){throw notFound("Workspace");}return workspaces.findById(id).filter(v->v.projectId().equals(project)).orElseThrow(()->notFound("Workspace"));}
    private WorkspaceCheckoutCredentialPort.CredentialLease credential(ProvisionCommand c,Workspace w,SourceRepository source){
        if(source.mcpConnectionId()!=null&&source.visibility()==SourceRepositoryVisibility.PRIVATE){
            return checkoutCredentials.acquire(new WorkspaceCheckoutCredentialPort.Request(
                    c.tenantId(),c.userId(),w.id(),source.id(),source.mcpConnectionId(),
                    source.providerRepositoryId(),source.remoteUrl()));
        }
        return fixed(null);
    }
    private static WorkspaceCheckoutCredentialPort.CredentialLease fixed(String header){return new WorkspaceCheckoutCredentialPort.CredentialLease(){public String authorizationHeader(){return header;}public void close(){}public String toString(){return "WorkspaceCheckoutCredentialLease[authorizationHeader="+(header==null?"<none>":"<redacted>")+"]";}};}
    private static BusinessException notFound(String n){return new BusinessException(n+" not found",HttpStatus.NOT_FOUND,"WORKSPACE_REFERENCE_NOT_FOUND");}
    private static String normalizeIsolationKey(String value){String key=value==null||value.isBlank()?"primary":value.trim();if(!key.matches("[A-Za-z0-9:_-]{1,128}"))throw new BusinessException("Invalid Workspace isolation key",HttpStatus.BAD_REQUEST,"WORKSPACE_ISOLATION_KEY_INVALID");return key;}
    private static WorkspaceView view(Workspace w){return new WorkspaceView(w.id(),w.projectId(),w.projectDirectoryId(),w.taskId(),w.sourceRepositoryId(),w.bridgeId(),w.isolationKey(),w.mode(),w.worktreeKey(),w.baseRef(),w.branchName(),w.worktreeRef(),w.headCommit(),w.writable(),w.state(),w.failureReason(),w.revision(),w.createdAt(),w.updatedAt());}
    private static BridgeCommandView commandView(BridgeWorkspaceCommand c){return new BridgeCommandView(c.id(),c.workspaceId(),c.bridgeId(),c.rootHandle(),c.baseRef(),c.branchName(),c.worktreeKey(),c.state(),c.createdAt());}
}
