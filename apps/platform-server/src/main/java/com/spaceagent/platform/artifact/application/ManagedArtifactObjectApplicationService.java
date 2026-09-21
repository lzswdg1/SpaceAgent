package com.spaceagent.platform.artifact.application;
import com.spaceagent.platform.artifact.api.*;
import com.spaceagent.platform.artifact.domain.*;
import com.spaceagent.platform.artifact.infrastructure.ArtifactObjectStorageProperties;
import com.spaceagent.platform.identity.api.IdentityOwnershipPort;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
@Service
public class ManagedArtifactObjectApplicationService implements ArtifactObjectApplicationApi {
    private final ArtifactObjectRepository repository;
    private final ArtifactObjectStorageGateway storage;
    private final ArtifactObjectOwnerScopeGateway owners;
    private final ArtifactObjectMaintenanceApplicationApi maintenance;
    private final IdentityOwnershipPort identities;
    private final IdGenerator ids;
    private final String encryption;
    private final long maximumObjectBytes;
    private final long maximumStoredBytesPerTenant;
    private final ArtifactObjectRepository.StagingQuota stagingQuota;
    public ManagedArtifactObjectApplicationService(ArtifactObjectRepository repository,ArtifactObjectStorageGateway storage,ArtifactObjectOwnerScopeGateway owners,ArtifactObjectMaintenanceApplicationApi maintenance,IdentityOwnershipPort identities,IdGenerator ids,ArtifactObjectStorageProperties properties){
        this.repository=repository;
        this.storage=storage;
        this.owners=owners;
        this.maintenance=maintenance;
        this.identities=identities;
        this.ids=ids;
        this.encryption=properties.getEncryptionReference();
        this.maximumObjectBytes=properties.getMaximumObjectBytes();
        this.maximumStoredBytesPerTenant=properties.getMaximumStoredBytesPerTenant();
        this.stagingQuota=new ArtifactObjectRepository.StagingQuota(properties.getMaximumActiveStagingSessionsPerUser(),properties.getMaximumActiveStagingBytesPerUser(),properties.getMaximumActiveStagingSessionsPerTenant(),properties.getMaximumActiveStagingBytesPerTenant(),maximumStoredBytesPerTenant);
        if(maximumObjectBytes<1||maximumObjectBytes>ManagedArtifactObject.MAX_BYTES||maximumObjectBytes>stagingQuota.maximumBytesPerUser()||maximumStoredBytesPerTenant<maximumObjectBytes)throw new IllegalStateException("Artifact object quota configuration is invalid");
    }
    @Override public StagingView beginStaging(BeginStagingCommand c){
        member(c.tenantId(),c.actorUserId());
        if(c.ttlSeconds()<60||c.ttlSeconds()>3600)throw bad("Artifact staging TTL is invalid");
        var previous=repository.findStagingByRequest(c.tenantId(),c.actorUserId(),c.requestId());
        if(previous.isPresent()){
            var v=previous.get();
            if(!v.expectedSha256().equals(c.expectedSha256())||v.expectedBytes()!=c.expectedBytes()||!v.mediaType().equals(c.mediaType()))throw conflict("Artifact staging idempotency conflict");
            return staging(v);
        }
        if(c.expectedBytes()>maximumObjectBytes)throw tooLarge();
        Instant now=repository.currentTime();
        String id=ids.nextId();
        var open=new ArtifactObjectStagingSession(id,c.tenantId(),c.actorUserId(),c.requestId(),c.expectedSha256(),c.expectedBytes(),c.mediaType(),encryption,null,ArtifactObjectStagingSession.State.OPEN,null,1,now.plusSeconds(c.ttlSeconds()),now,now);
        try{
            repository.insertStaging(open,stagingQuota);
        }
        catch(ArtifactObjectQuotaExceededException error){
            throw quota(error);
        }
        try{
            String ref=storage.createStaging(new ArtifactObjectStorageGateway.CreateStagingCommand(c.tenantId(),id,c.expectedBytes())).stagingReference();
            return staging(repository.updateStaging(open.provision(ref,repository.currentTime()),open.revision()).orElseThrow());
        }
        catch(RuntimeException error){
            throw new BusinessException("Artifact staging is unavailable",HttpStatus.SERVICE_UNAVAILABLE,"ARTIFACT_STAGING_UNAVAILABLE");
        }
    }
    @Override public StagingView getStaging(GetStagingQuery q){
        return staging(requireStaging(q.tenantId(),q.actorUserId(),q.stagingId()));
    }
    @Override public StagingView upload(UploadChunkCommand c){
        var session=requireStaging(c.tenantId(),c.actorUserId(),c.stagingId());
        if(session.state()!=ArtifactObjectStagingSession.State.UPLOADING)throw conflict("Artifact staging is not uploadable");
        byte[] bytes;
        try{
            bytes=Base64.getDecoder().decode(c.chunkBase64());
        }
        catch(RuntimeException e){
            throw bad("Artifact chunk base64 is invalid");
        }
        storage.write(new ArtifactObjectStorageGateway.WriteCommand(c.tenantId(),session.stagingReference(),c.expectedOffset(),bytes,session.expectedBytes()));
        return staging(session);
    }
    @Override public StagingView verify(VerifyStagingCommand c){
        var session=requireStaging(c.tenantId(),c.actorUserId(),c.stagingId());
        revision(session.revision(),c.expectedRevision());
        var proof=storage.verify(new ArtifactObjectStorageGateway.VerifyCommand(c.tenantId(),session.stagingReference(),session.expectedSha256(),session.expectedBytes()));
        var verified=session.verify(proof.contentSha256(),proof.byteSize(),session.stagingReference(),repository.currentTime());
        return staging(repository.updateStaging(verified,session.revision()).orElseThrow(ManagedArtifactObjectApplicationService::revisionConflict));
    }
    @Override public ObjectView publish(PublishStagingCommand c){
        var session=requireStaging(c.tenantId(),c.actorUserId(),c.stagingId());
        if(session.state()==ArtifactObjectStagingSession.State.PUBLISHED){
            cleanupStaging(session.tenantId(),session.stagingReference());
            return object(requireObjectById(c.tenantId(),session.publishedObjectId()));
        }
        revision(session.revision(),c.expectedRevision());
        if(session.state()!=ArtifactObjectStagingSession.State.VERIFIED)throw conflict("Artifact staging is not verified");
        Instant now=repository.currentTime();
        if(c.retainUntil()==null||c.retainUntil().isBefore(now)||c.retainUntil().isAfter(now.plus(3650,ChronoUnit.DAYS)))throw bad("Artifact retention is invalid");
        var stored=storage.publish(new ArtifactObjectStorageGateway.PublishCommand(c.tenantId(),session.stagingReference(),session.expectedSha256(),session.expectedBytes(),session.encryptionReference()));
        var proposed=new ManagedArtifactObject(ids.nextId(),c.tenantId(),c.actorUserId(),session.expectedSha256(),session.expectedBytes(),session.mediaType(),stored.storageReference(),session.encryptionReference(),new ManagedArtifactObject.Retention(c.retainUntil(),c.deleteWhenUnreferenced()),ManagedArtifactObject.State.READY,1,now,now,null);
        ArtifactObjectRepository.PublishResult published;
        try{
            published=repository.publish(c.tenantId(),session.id(),session.revision(),proposed,now,maximumStoredBytesPerTenant);
        }
        catch(ArtifactObjectQuotaExceededException error){
            throw quota(error);
        }
        cleanupStaging(session.tenantId(),session.stagingReference());
        if(published.redundantStagingReference()!=null)cleanupStaging(session.tenantId(),published.redundantStagingReference());
        return object(published.object());
    }
    @Override public ObjectView get(GetObjectQuery q){
        return object(requireObject(q.tenantId(),q.actorUserId(),q.objectId()));
    }
    @Override public ObjectBytesView read(ReadObjectQuery q){
        var object=requireObject(q.tenantId(),q.actorUserId(),q.objectId());
        var bytes=storage.read(new ArtifactObjectStorageGateway.ReadQuery(q.tenantId(),object.storageReference(),q.offset(),q.maximumBytes()));
        return new ObjectBytesView(object.id(),Base64.getEncoder().encodeToString(bytes.bytes()),bytes.totalBytes(),bytes.truncated());
    }
    @Override public DownloadCapabilityView createDownloadCapability(DownloadCapabilityCommand c){
        var object=requireObject(c.tenantId(),c.actorUserId(),c.objectId());
        try{
            var value=storage.presignDownload(new ArtifactObjectStorageGateway.PresignDownloadQuery(c.tenantId(),object.storageReference(),c.ttlSeconds()));
            return new DownloadCapabilityView(object.id(),value.url(),value.expiresAt());
        }
        catch(UnsupportedOperationException unavailable){
            throw new BusinessException("Artifact download capability is unavailable",HttpStatus.SERVICE_UNAVAILABLE,"ARTIFACT_DOWNLOAD_CAPABILITY_UNAVAILABLE");
        }
    }
    @Override public ReferenceView attach(AttachReferenceCommand c){
        ManagedArtifactObject object=requireObject(c.tenantId(),c.actorUserId(),c.objectId());
        if(object.state()!=ManagedArtifactObject.State.READY||!owners.canAttach(c.tenantId(),c.actorUserId(),c.ownerType(),c.ownerResourceId()))throw forbidden();
        var prior=repository.references(c.tenantId(),c.objectId()).stream().filter(v->v.ownerType()==c.ownerType()&&v.ownerResourceId().equals(c.ownerResourceId())&&v.purpose().equals(c.purpose())).findFirst();
        if(prior.isPresent())return reference(prior.get());
        Instant now=repository.currentTime();
        var value=new ArtifactObjectReference(ids.nextId(),c.tenantId(),c.objectId(),c.ownerType(),c.ownerResourceId(),c.purpose(),ArtifactObjectReference.State.ACTIVE,1,now,null);
        try{
            repository.insertReference(value);
        }
        catch(ArtifactObjectLifecycleConflictException denied){
            throw conflict(denied.getMessage());
        }
        return reference(value);
    }
    @Override public ReferenceView release(ReleaseReferenceCommand c){
        member(c.tenantId(),c.actorUserId());
        var current=repository.findReference(c.tenantId(),c.referenceId()).orElseThrow(ManagedArtifactObjectApplicationService::missing);
        if(!owners.canAttach(c.tenantId(),c.actorUserId(),current.ownerType(),current.ownerResourceId()))throw forbidden();
        revision(current.revision(),c.expectedRevision());
        return reference(repository.updateReference(current.release(repository.currentTime()),current.revision()).orElseThrow(ManagedArtifactObjectApplicationService::revisionConflict));
    }
    @Override public HoldView placeHold(PlaceHoldCommand c){
        var object=requireObject(c.tenantId(),c.actorUserId(),c.objectId());
        if(object.state()!=ManagedArtifactObject.State.READY)throw conflict("Artifact deletion already admitted");
        var existing=repository.holds(c.tenantId(),c.objectId()).stream().filter(v->v.state()==ArtifactObjectLegalHold.State.ACTIVE).findFirst();
        if(existing.isPresent())return hold(existing.get());
        var value=new ArtifactObjectLegalHold(ids.nextId(),c.tenantId(),c.objectId(),c.reasonSha256(),c.actorUserId(),ArtifactObjectLegalHold.State.ACTIVE,1,repository.currentTime(),null,null);
        try{
            repository.insertHold(value);
        }
        catch(ArtifactObjectLifecycleConflictException denied){
            throw conflict(denied.getMessage());
        }
        return hold(value);
    }
    @Override public HoldView releaseHold(ReleaseHoldCommand c){
        member(c.tenantId(),c.actorUserId());
        var current=repository.findHold(c.tenantId(),c.holdId()).orElseThrow(ManagedArtifactObjectApplicationService::missing);
        requireObject(c.tenantId(),c.actorUserId(),current.objectId());
        revision(current.revision(),c.expectedRevision());
        return hold(repository.updateHold(current.release(c.actorUserId(),repository.currentTime()),current.revision()).orElseThrow(ManagedArtifactObjectApplicationService::revisionConflict));
    }
    @Override public ObjectView requestDeletion(RequestDeletionCommand c){
        var current=requireObject(c.tenantId(),c.actorUserId(),c.objectId());
        revision(current.revision(),c.expectedRevision());
        maintenance.scheduleDeletion(new ArtifactObjectMaintenanceApplicationApi.ScheduleDeletionCommand(c.tenantId(),c.objectId()));
        return object(requireObjectById(c.tenantId(),c.objectId()));
    }
    private ArtifactObjectStagingSession requireStaging(String t,String a,String id){
        member(t,a);
        return repository.findStaging(t,id).filter(v->v.requestedBy().equals(a)).orElseThrow(ManagedArtifactObjectApplicationService::missing);
    }
    private ManagedArtifactObject requireObject(String t,String a,String id){
        member(t,a);
        var value=requireObjectById(t,id);
        boolean access=value.createdBy().equals(a)||repository.references(t,id).stream().filter(v->v.state()==ArtifactObjectReference.State.ACTIVE).anyMatch(v->owners.canAttach(t,a,v.ownerType(),v.ownerResourceId()));
        if(!access)throw forbidden();
        return value;
    }
    private ManagedArtifactObject requireObjectById(String t,String id){
        return repository.findObject(t,id).orElseThrow(ManagedArtifactObjectApplicationService::missing);
    }
    private void member(String t,String a){
        if(!identities.isMemberOfTenant(t,a))throw forbidden();
    }
    private static void revision(long actual,long expected){
        if(actual!=expected)throw revisionConflict();
    }
    private void cleanupStaging(String tenantId,String stagingReference){
        try{
            storage.deleteStaging(new ArtifactObjectStorageGateway.DeleteStagingCommand(tenantId,stagingReference));
        }
        catch(RuntimeException ignored){
            /* Durable publish already succeeded; retry remains idempotent. */
        }
    }
    private ObjectView object(ManagedArtifactObject v){
        long refs=repository.references(v.tenantId(),v.id()).stream().filter(r->r.state()==ArtifactObjectReference.State.ACTIVE).count();
        boolean held=repository.holds(v.tenantId(),v.id()).stream().anyMatch(h->h.state()==ArtifactObjectLegalHold.State.ACTIVE);
        return new ObjectView(v.id(),v.contentSha256(),v.byteSize(),v.mediaType(),v.state().name(),v.retention().retainUntil(),v.retention().deleteWhenUnreferenced(),refs,held,v.revision(),v.createdAt(),v.updatedAt(),v.deletedAt());
    }
    private static StagingView staging(ArtifactObjectStagingSession v){
        return new StagingView(v.id(),v.expectedSha256(),v.expectedBytes(),v.mediaType(),v.state().name(),v.publishedObjectId(),v.revision(),v.expiresAt(),v.createdAt(),v.updatedAt());
    }
    private static ReferenceView reference(ArtifactObjectReference v){
        return new ReferenceView(v.id(),v.objectId(),v.ownerType().name(),v.ownerResourceId(),v.purpose(),v.state().name(),v.revision(),v.createdAt(),v.releasedAt());
    }
    private static HoldView hold(ArtifactObjectLegalHold v){
        return new HoldView(v.id(),v.objectId(),v.reasonSha256(),v.state().name(),v.revision(),v.placedAt(),v.releasedAt());
    }
    private static BusinessException bad(String m){
        return new BusinessException(m,HttpStatus.BAD_REQUEST,"ARTIFACT_OBJECT_INPUT_INVALID");
    }
    private static BusinessException tooLarge(){
        return new BusinessException("Artifact object exceeds the configured size limit",HttpStatus.PAYLOAD_TOO_LARGE,"ARTIFACT_OBJECT_SIZE_LIMIT");
    }
    private static BusinessException quota(ArtifactObjectQuotaExceededException error){
        return new BusinessException("Artifact object quota exceeded",HttpStatus.TOO_MANY_REQUESTS,"ARTIFACT_"+error.limit().name()+"_QUOTA_EXCEEDED");
    }
    private static BusinessException conflict(String m){
        return new BusinessException(m,HttpStatus.CONFLICT,"ARTIFACT_OBJECT_STATE_CONFLICT");
    }
    private static BusinessException missing(){
        return new BusinessException("Artifact object not found",HttpStatus.NOT_FOUND,"ARTIFACT_OBJECT_NOT_FOUND");
    }
    private static BusinessException forbidden(){
        return new BusinessException("Artifact object access denied",HttpStatus.FORBIDDEN,"ARTIFACT_OBJECT_ACCESS_DENIED");
    }
    private static BusinessException revisionConflict(){
        return new BusinessException("Artifact object revision conflict",HttpStatus.CONFLICT,"ARTIFACT_OBJECT_REVISION_CONFLICT");
    }
}
