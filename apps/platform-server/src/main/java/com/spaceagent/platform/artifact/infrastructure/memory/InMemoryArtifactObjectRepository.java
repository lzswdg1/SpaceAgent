package com.spaceagent.platform.artifact.infrastructure.memory;
import com.spaceagent.platform.artifact.domain.*;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.*;
@Repository
@ConditionalOnProperty(prefix="platform",name="persistence",havingValue="memory",matchIfMissing=true)
public class InMemoryArtifactObjectRepository implements ArtifactObjectRepository {
    private final Map<String,ArtifactObjectStagingSession> staging=new HashMap<>();
    private final Map<String,ManagedArtifactObject> objects=new HashMap<>();
    private final Map<String,ArtifactObjectReference> references=new HashMap<>();
    private final Map<String,ArtifactObjectLegalHold> holds=new HashMap<>();
    private final Map<String,ArtifactObjectDeletionJob> deletions=new HashMap<>();
    private final TimeProvider time;
    public InMemoryArtifactObjectRepository(TimeProvider time){
        this.time=time;
    }
    public Instant currentTime(){
        return time.now();
    }
    public synchronized void insertStaging(ArtifactObjectStagingSession v){
        if(findStagingByRequest(v.tenantId(),v.requestedBy(),v.requestId()).isPresent()||staging.putIfAbsent(v.id(),v)!=null)throw new IllegalStateException("Artifact staging exists");
    }
    @Override public synchronized void insertStaging(ArtifactObjectStagingSession v,StagingQuota q){
        var active=staging.values().stream().filter(InMemoryArtifactObjectRepository::reservesStaging).toList();
        long userCount=active.stream().filter(s->s.tenantId().equals(v.tenantId())&&s.requestedBy().equals(v.requestedBy())).count();
        long userBytes=active.stream().filter(s->s.tenantId().equals(v.tenantId())&&s.requestedBy().equals(v.requestedBy())).mapToLong(ArtifactObjectStagingSession::expectedBytes).sum();
        long tenantCount=active.stream().filter(s->s.tenantId().equals(v.tenantId())).count();
        long tenantBytes=active.stream().filter(s->s.tenantId().equals(v.tenantId())).mapToLong(ArtifactObjectStagingSession::expectedBytes).sum();
        long storedBytes=objects.values().stream().filter(o->o.tenantId().equals(v.tenantId())&&o.state()!=ManagedArtifactObject.State.DELETED).mapToLong(ManagedArtifactObject::byteSize).sum();
        if(userCount>=q.maximumSessionsPerUser()||exceeds(userBytes,v.expectedBytes(),q.maximumBytesPerUser()))throw new ArtifactObjectQuotaExceededException(ArtifactObjectQuotaExceededException.Limit.USER_STAGING);
        if(tenantCount>=q.maximumSessionsPerTenant()||exceeds(tenantBytes,v.expectedBytes(),q.maximumBytesPerTenant()))throw new ArtifactObjectQuotaExceededException(ArtifactObjectQuotaExceededException.Limit.TENANT_STAGING);
        if(exceeds(storedBytes,tenantBytes,q.maximumStoredBytesPerTenant())||exceeds(storedBytes+tenantBytes,v.expectedBytes(),q.maximumStoredBytesPerTenant()))throw new ArtifactObjectQuotaExceededException(ArtifactObjectQuotaExceededException.Limit.TENANT_STORAGE);
        insertStaging(v);
    }
    public synchronized Optional<ArtifactObjectStagingSession> findStaging(String t,String id){
        return Optional.ofNullable(staging.get(id)).filter(v->v.tenantId().equals(t));
    }
    public synchronized Optional<ArtifactObjectStagingSession> findStagingByRequest(String t,String a,String r){
        return staging.values().stream().filter(v->v.tenantId().equals(t)&&v.requestedBy().equals(a)&&v.requestId().equals(r)).findFirst();
    }
    public synchronized Optional<ArtifactObjectStagingSession> updateStaging(ArtifactObjectStagingSession v,long r){
        var c=staging.get(v.id());
        if(c==null||c.revision()!=r||v.revision()!=r+1)return Optional.empty();
        staging.put(v.id(),v);
        return Optional.of(v);
    }
    public synchronized PublishResult publish(String tenant, String id, long revision, ManagedArtifactObject proposed,
    Instant at, long maximumStoredBytesPerTenant) {
        var current = findStaging(tenant, id).orElseThrow();
        if (current.revision() != revision || current.state() != ArtifactObjectStagingSession.State.VERIFIED)
        throw new IllegalStateException("Artifact staging publish conflict");
        long stored = objects.values().stream().filter(v -> v.tenantId().equals(tenant) && v.state() != ManagedArtifactObject.State.DELETED)
        .mapToLong(ManagedArtifactObject::byteSize).sum();
        if (exceeds(stored, proposed.byteSize(), maximumStoredBytesPerTenant))
        throw new ArtifactObjectQuotaExceededException(ArtifactObjectQuotaExceededException.Limit.TENANT_STORAGE);
        if (objects.putIfAbsent(proposed.id(), proposed) != null) throw new IllegalStateException("Artifact identity exists");
        var published = current.publish(proposed.id(), at);
        staging.put(id, published);
        return new PublishResult(published, proposed, false, null);
    }
    public synchronized Optional<ManagedArtifactObject> findObject(String t,String id){
        return Optional.ofNullable(objects.get(id)).filter(v->v.tenantId().equals(t));
    }
    public synchronized Optional<ManagedArtifactObject> updateObject(ManagedArtifactObject v,long r){
        var c=objects.get(v.id());
        if(c==null||c.revision()!=r||v.revision()!=r+1)return Optional.empty();
        objects.put(v.id(),v);
        return Optional.of(v);
    }
    public synchronized void insertReference(ArtifactObjectReference v){
        requireReady(v.tenantId(),v.objectId());
        if(references.putIfAbsent(v.id(),v)!=null)throw new IllegalStateException("Artifact reference exists");
    }
    public synchronized Optional<ArtifactObjectReference> findReference(String t,String id){
        return Optional.ofNullable(references.get(id)).filter(v->v.tenantId().equals(t));
    }
    public synchronized Optional<ArtifactObjectReference> updateReference(ArtifactObjectReference v,long r){
        var c=references.get(v.id());
        if(c==null||c.revision()!=r||v.revision()!=r+1)return Optional.empty();
        references.put(v.id(),v);
        return Optional.of(v);
    }
    public synchronized List<ArtifactObjectReference> references(String t,String o){
        return references.values().stream().filter(v->v.tenantId().equals(t)&&v.objectId().equals(o)).toList();
    }
    public synchronized void insertHold(ArtifactObjectLegalHold v){
        requireReady(v.tenantId(),v.objectId());
        if(holds.putIfAbsent(v.id(),v)!=null||holds.values().stream().filter(h->!h.id().equals(v.id())).anyMatch(h->h.tenantId().equals(v.tenantId())&&h.objectId().equals(v.objectId())&&h.state()==ArtifactObjectLegalHold.State.ACTIVE))throw new IllegalStateException("Artifact hold exists");
    }
    public synchronized Optional<ArtifactObjectLegalHold> findHold(String t,String id){
        return Optional.ofNullable(holds.get(id)).filter(v->v.tenantId().equals(t));
    }
    public synchronized Optional<ArtifactObjectLegalHold> updateHold(ArtifactObjectLegalHold v,long r){
        var c=holds.get(v.id());
        if(c==null||c.revision()!=r||v.revision()!=r+1)return Optional.empty();
        holds.put(v.id(),v);
        return Optional.of(v);
    }
    public synchronized List<ArtifactObjectLegalHold> holds(String t,String o){
        return holds.values().stream().filter(v->v.tenantId().equals(t)&&v.objectId().equals(o)).toList();
    }
    public synchronized void insertDeletion(ArtifactObjectDeletionJob v){
        if(deletions.values().stream().anyMatch(d->d.objectId().equals(v.objectId()))||deletions.putIfAbsent(v.id(),v)!=null)throw new IllegalStateException("Artifact deletion exists");
    }
    public synchronized Optional<ArtifactObjectDeletionJob> scheduleDeletion(ManagedArtifactObject object,long expected,ArtifactObjectDeletionJob job){
        var current=objects.get(object.id());
        if(current==null||current.revision()!=expected||object.revision()!=expected+1||hasBlockers(current.tenantId(),current.id())||current.retention().retainUntil().isAfter(currentTime())||current.state()!=ManagedArtifactObject.State.READY||deletions.values().stream().anyMatch(v->v.objectId().equals(object.id())))return Optional.empty();
        objects.put(object.id(),object);
        deletions.put(job.id(),job);
        return Optional.of(job);
    }
    public synchronized Optional<ArtifactObjectDeletionJob> findDeletion(String t,String o){
        return deletions.values().stream().filter(v->v.tenantId().equals(t)&&v.objectId().equals(o)).findFirst();
    }
    public synchronized Optional<ArtifactObjectDeletionJob> claimDeletion(String worker, String token, int seconds) {
        Instant now = currentTime();
        var pending = deletions.values().stream().filter(v -> v.state() == ArtifactObjectDeletionJob.State.PENDING)
        .min(Comparator.comparing(ArtifactObjectDeletionJob::createdAt));
        if (pending.isEmpty()) return Optional.empty();
        var v = pending.get();
        var claimed = new ArtifactObjectDeletionJob(v.id(), v.tenantId(), v.objectId(), ArtifactObjectDeletionJob.State.CLAIMED,
        token, worker, now.plusSeconds(seconds), v.fencingToken()+1, null, v.revision()+1, v.createdAt(), now, null);
        deletions.put(v.id(), claimed);
        var object = findObject(v.tenantId(), v.objectId()).orElseThrow();
        if (object.state() != ManagedArtifactObject.State.DELETE_PENDING || hasBlockers(v.tenantId(), v.objectId())) {
            var blocked = object.state() == ManagedArtifactObject.State.DELETE_PENDING ? object.blockDeletion(now) : object;
            finishDeletion(v.tenantId(), v.id(), claimed.revision(), token, claimed.fencingToken(), blocked,
            object.revision(), ArtifactObjectDeletionJob.State.BLOCKED, "ARTIFACT_DELETE_PERMISSION_BLOCKED", now).orElseThrow();
            return Optional.empty();
        }
        return Optional.of(claimed);
    }
    public synchronized Optional<ArtifactObjectDeletionJob> finishDeletion(String t,String id,long rev,String token,long fence,ManagedArtifactObject object,long objectRevision,ArtifactObjectDeletionJob.State state,String code,Instant at){
        ArtifactObjectDeletionJob v=deletions.get(id);
        ManagedArtifactObject current=objects.get(object.id());
        if(v==null||current==null||!v.tenantId().equals(t)||v.state()!=ArtifactObjectDeletionJob.State.CLAIMED||v.revision()!=rev||!token.equals(v.claimToken())||fence!=v.fencingToken()||current.revision()!=objectRevision||object.revision()!=objectRevision+1||!List.of(ArtifactObjectDeletionJob.State.BLOCKED,ArtifactObjectDeletionJob.State.COMPLETED).contains(state))return Optional.empty();
        var done=new ArtifactObjectDeletionJob(v.id(),v.tenantId(),v.objectId(),state,null,null,null,v.fencingToken(),code,v.revision()+1,v.createdAt(),at,state==ArtifactObjectDeletionJob.State.COMPLETED?at:null);
        objects.put(object.id(),object);
        deletions.put(id,done);
        return Optional.of(done);
    }
    public synchronized List<ArtifactObjectStagingSession> findExpiredStaging(Instant at,int limit){
        return staging.values().stream().filter(v->!v.expiresAt().isAfter(at)&&List.of(ArtifactObjectStagingSession.State.OPEN,ArtifactObjectStagingSession.State.UPLOADING,ArtifactObjectStagingSession.State.VERIFIED,ArtifactObjectStagingSession.State.UNKNOWN).contains(v.state())).sorted(Comparator.comparing(ArtifactObjectStagingSession::expiresAt)).limit(limit).toList();
    }
    public synchronized Optional<ArtifactObjectDeletionJob> recoverExpiredDeletion(Instant at){
        return deletions.values().stream().filter(v->v.state()==ArtifactObjectDeletionJob.State.CLAIMED&&!v.leaseUntil().isAfter(at)).findFirst().map(v->{
            var object=objects.get(v.objectId());if(object!=null&&object.state()==ManagedArtifactObject.State.DELETE_PENDING)objects.put(object.id(),object.blockDeletion(at));var blocked=new ArtifactObjectDeletionJob(v.id(),v.tenantId(),v.objectId(),ArtifactObjectDeletionJob.State.BLOCKED,null,null,null,v.fencingToken(),"ARTIFACT_DELETE_OUTCOME_UNKNOWN",v.revision()+1,v.createdAt(),at,null);deletions.put(v.id(),blocked);return blocked;
        });
    }
    public synchronized Optional<ArtifactObjectDeletionJob> retryBlockedDeletion(String tenant,String objectId,Instant at){
        var object=objects.get(objectId);
        var job=deletions.values().stream().filter(v->v.tenantId().equals(tenant)&&v.objectId().equals(objectId)).findFirst().orElse(null);
        if(object==null||!object.tenantId().equals(tenant)||object.state()!=ManagedArtifactObject.State.BLOCKED||job==null||job.state()!=ArtifactObjectDeletionJob.State.BLOCKED)return Optional.empty();
        boolean referenced=references.values().stream().anyMatch(v->v.tenantId().equals(tenant)&&v.objectId().equals(objectId)&&v.state()==ArtifactObjectReference.State.ACTIVE);
        boolean held=holds.values().stream().anyMatch(v->v.tenantId().equals(tenant)&&v.objectId().equals(objectId)&&v.state()==ArtifactObjectLegalHold.State.ACTIVE);
        if(referenced||held)return Optional.empty();
        objects.put(objectId,object.retryDeletion(at));
        var retried=job.retry(at);
        deletions.put(job.id(),retried);
        return Optional.of(retried);
    }
    private static boolean reservesStaging(ArtifactObjectStagingSession v){
        return List.of(ArtifactObjectStagingSession.State.OPEN,ArtifactObjectStagingSession.State.UPLOADING,ArtifactObjectStagingSession.State.VERIFIED,ArtifactObjectStagingSession.State.UNKNOWN).contains(v.state());
    }
    private void requireReady(String tenant, String id) {
        if (findObject(tenant,id).orElseThrow().state() != ManagedArtifactObject.State.READY) throw new ArtifactObjectLifecycleConflictException();
    }
    private boolean hasBlockers(String tenant, String id) {
        return references(tenant,id).stream().anyMatch(r -> r.state()==ArtifactObjectReference.State.ACTIVE)
        || holds(tenant,id).stream().anyMatch(h -> h.state()==ArtifactObjectLegalHold.State.ACTIVE);
    }
    private static boolean exceeds(long current,long added,long maximum){
        return current>maximum-added;
    }
}
