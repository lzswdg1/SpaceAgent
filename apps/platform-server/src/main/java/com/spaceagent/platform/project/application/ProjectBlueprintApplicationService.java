package com.spaceagent.platform.project.application;
import com.fasterxml.jackson.databind.ObjectMapper; import com.spaceagent.platform.memory.api.*; import com.spaceagent.platform.memory.domain.MemoryKind;
import com.spaceagent.platform.project.api.ProjectBlueprintApplicationApi; import com.spaceagent.platform.project.domain.*;
import com.spaceagent.shared.exception.BusinessException; import com.spaceagent.shared.id.IdGenerator; import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
import java.time.Instant; import java.util.List; import java.util.UUID;
@Service @Transactional
public class ProjectBlueprintApplicationService implements ProjectBlueprintApplicationApi {
    private final ProjectBlueprintRepository repo;private final SourceRepositoryRepository sources;private final ProjectAccessPolicy access;
    private final MemoryApplicationApi memory;private final ObjectMapper mapper;private final IdGenerator ids;private final TimeProvider time;
    public ProjectBlueprintApplicationService(ProjectBlueprintRepository r,SourceRepositoryRepository s,ProjectAccessPolicy a,MemoryApplicationApi m,ObjectMapper o,IdGenerator i,TimeProvider t){repo=r;sources=s;access=a;memory=m;mapper=o;ids=i;time=t;}
    public BlueprintView create(CreateCommand c){Project p=manage(c.tenantId(),c.userId(),c.projectId());if(c.sourceRepositoryId()!=null&&!c.sourceRepositoryId().isBlank())sources.findById(c.sourceRepositoryId()).filter(v->v.projectId().equals(p.id())).orElseThrow(()->notFound());
        Instant now=time.now();ProjectBlueprint b=new ProjectBlueprint(ids.nextId(),c.tenantId(),p.id(),repo.nextVersion(p.id()),ProjectBlueprintStatus.DRAFT,c.source()==null?ProjectBlueprintSource.USER:c.source(),c.sourceRepositoryId(),c.generatedByAgentId(),c.generatedByRunConfigurationSnapshotId(),c.userId(),null,null,c.document(),now,now);repo.save(b);return view(b);}
    public BlueprintView confirm(ConfirmCommand c){manage(c.tenantId(),c.userId(),c.projectId());ProjectBlueprint b=require(c.projectId(),c.blueprintId());if(b.status()!=ProjectBlueprintStatus.DRAFT)throw new BusinessException("Only DRAFT Blueprint can be confirmed",HttpStatus.CONFLICT,"BLUEPRINT_STATE_CONFLICT");Instant now=time.now();
        repo.findConfirmed(c.projectId()).ifPresent(old->repo.save(copy(old,ProjectBlueprintStatus.SUPERSEDED,old.confirmedBy(),old.confirmedAt(),now)));
        ProjectBlueprint confirmed=copy(b,ProjectBlueprintStatus.CONFIRMED,c.userId(),now,now);repo.save(confirmed);
        try{memory.saveProjectSnapshot(new SaveProjectMemorySnapshotCommand(c.projectId(),MemoryKind.ARCHITECTURE,"project-blueprint",mapper.writeValueAsString(confirmed.document())));}catch(Exception e){throw new IllegalStateException("Unable to project Blueprint memory",e);}return view(confirmed);}
    @Transactional(readOnly=true) public BlueprintView get(Query q){access.requireProject(q.tenantId(),q.userId(),q.projectId());return view(require(q.projectId(),q.blueprintId()));}
    @Transactional(readOnly=true) public List<BlueprintView> list(ListQuery q){access.requireProject(q.tenantId(),q.userId(),q.projectId());return repo.findByProjectId(q.projectId()).stream().map(ProjectBlueprintApplicationService::view).toList();}
    private Project manage(String t,String u,String p){Project v=access.requireProject(t,u,p);access.requireActive(v);access.requireRole(v,u,ProjectRole::canModify);return v;}
    private ProjectBlueprint require(String p,String id){try{UUID.fromString(id);}catch(Exception e){throw notFound();}return repo.findById(id).filter(v->v.projectId().equals(p)).orElseThrow(ProjectBlueprintApplicationService::notFound);}
    private static ProjectBlueprint copy(ProjectBlueprint b,ProjectBlueprintStatus s,String by,Instant at,Instant updated){return new ProjectBlueprint(b.id(),b.tenantId(),b.projectId(),b.versionNumber(),s,b.source(),b.sourceRepositoryId(),b.generatedByAgentId(),b.generatedByRunConfigurationSnapshotId(),b.createdBy(),by,at,b.document(),b.createdAt(),updated);}
    private static BlueprintView view(ProjectBlueprint b){return new BlueprintView(b.id(),b.projectId(),b.versionNumber(),b.status(),b.source(),b.sourceRepositoryId(),b.generatedByAgentId(),b.generatedByRunConfigurationSnapshotId(),b.createdBy(),b.confirmedBy(),b.confirmedAt(),b.document(),b.createdAt(),b.updatedAt());}
    private static BusinessException notFound(){return new BusinessException("ProjectBlueprint not found",HttpStatus.NOT_FOUND,"PROJECT_BLUEPRINT_NOT_FOUND");}
}
