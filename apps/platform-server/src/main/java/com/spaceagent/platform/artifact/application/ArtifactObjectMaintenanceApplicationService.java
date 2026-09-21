package com.spaceagent.platform.artifact.application;
import com.spaceagent.platform.artifact.api.ArtifactObjectMaintenanceApplicationApi;
import com.spaceagent.platform.artifact.domain.*;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.UUID;
@Service
public class ArtifactObjectMaintenanceApplicationService implements ArtifactObjectMaintenanceApplicationApi {
    private final ArtifactObjectRepository repository;
    private final ArtifactObjectStorageGateway storage;
    private final IdGenerator ids;
    public ArtifactObjectMaintenanceApplicationService(ArtifactObjectRepository repository,ArtifactObjectStorageGateway storage,IdGenerator ids){
        this.repository=repository;
        this.storage=storage;
        this.ids=ids;
    }
    @Override public DeletionView scheduleDeletion(ScheduleDeletionCommand c){
        var existing=repository.findDeletion(c.tenantId(),c.objectId());
        if(existing.isPresent())return view(existing.get());
        ManagedArtifactObject object=repository.findObject(c.tenantId(),c.objectId()).orElseThrow();
        long refs=repository.references(c.tenantId(),c.objectId()).stream().filter(v->v.state()==ArtifactObjectReference.State.ACTIVE).count();
        boolean hold=repository.holds(c.tenantId(),c.objectId()).stream().anyMatch(v->v.state()==ArtifactObjectLegalHold.State.ACTIVE);
        Instant now=repository.currentTime();
        ManagedArtifactObject pending=object.requestDeletion(refs,hold,now);
        var job=new ArtifactObjectDeletionJob(ids.nextId(),c.tenantId(),c.objectId(),ArtifactObjectDeletionJob.State.PENDING,null,null,null,0,null,1,now,now,null);
        return view(repository.scheduleDeletion(pending,object.revision(),job).orElseThrow(()->new IllegalStateException("Artifact deletion schedule conflict")));
    }
    @Override public boolean runDeletionOnce(String worker,int seconds){
        var claim=repository.claimDeletion(worker,UUID.randomUUID().toString(),seconds);
        if(claim.isEmpty())return false;
        var job=claim.get();
        ManagedArtifactObject object=repository.findObject(job.tenantId(),job.objectId()).orElseThrow();
        Instant now=repository.currentTime();
        ArtifactObjectDeletionJob.State state;
        String code;
        ManagedArtifactObject target;
        try{
            storage.deleteObject(new ArtifactObjectStorageGateway.DeleteObjectCommand(object.tenantId(),object.storageReference()));
            state=ArtifactObjectDeletionJob.State.COMPLETED;
            code=null;
            target=object.deleted(now);
        }
        catch(RuntimeException error){
            state=ArtifactObjectDeletionJob.State.BLOCKED;
            code="ARTIFACT_BYTES_DELETE_BLOCKED";
            target=object.blockDeletion(now);
        }
        repository.finishDeletion(job.tenantId(),job.id(),job.revision(),job.claimToken(),job.fencingToken(),target,object.revision(),state,code,now).orElseThrow(()->new IllegalStateException("Artifact deletion completion lost fence"));
        return true;
    }
    @Override public int sweepExpiredStaging(int limit){
        int bounded=Math.max(1,Math.min(limit,100));
        int removed=0;
        for(var session:repository.findExpiredStaging(repository.currentTime(),bounded)){
            String ref=session.stagingReference()==null?"artifact-staging:"+session.id():session.stagingReference();
            try{
                storage.deleteStaging(new ArtifactObjectStorageGateway.DeleteStagingCommand(session.tenantId(),ref));
                var rejected=session.reject(ref,repository.currentTime());
                if(repository.updateStaging(rejected,session.revision()).isPresent())removed++;
            }
            catch(RuntimeException ignored){
                /* Keep durable staging state for the next bounded sweep. */
            }
        }
        return removed;
    }
    @Override public boolean recoverExpiredDeletion(){
        return repository.recoverExpiredDeletion(repository.currentTime()).isPresent();
    }
    @Override public DeletionView retryBlockedDeletion(RetryBlockedDeletionCommand c){
        var retried=repository.retryBlockedDeletion(c.tenantId(),c.objectId(),repository.currentTime()).orElseThrow(()->new BusinessException("Artifact deletion is not blocked or is no longer eligible",HttpStatus.CONFLICT,"ARTIFACT_DELETION_RETRY_CONFLICT"));
        return view(retried);
    }
    private static DeletionView view(ArtifactObjectDeletionJob v){
        return new DeletionView(v.id(),v.objectId(),v.state().name(),v.fencingToken(),v.revision(),v.safeErrorCode());
    }
}
