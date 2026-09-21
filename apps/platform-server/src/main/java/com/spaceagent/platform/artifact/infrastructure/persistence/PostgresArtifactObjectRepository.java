package com.spaceagent.platform.artifact.infrastructure.persistence;
import com.spaceagent.platform.artifact.domain.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.time.Instant;
import java.util.*;
@Repository
@ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class PostgresArtifactObjectRepository implements ArtifactObjectRepository {
    private static final String S="id,tenant_id,requested_by,request_id,expected_sha256,expected_bytes,media_type,encryption_reference,staging_reference,state,published_object_id,revision,expires_at,created_at,updated_at";
    private static final String O="id,tenant_id,created_by,content_sha256,byte_size,media_type,storage_reference,encryption_reference,retain_until,delete_when_unreferenced,state,revision,created_at,updated_at,deleted_at";
    private static final String R="id,tenant_id,object_id,owner_type,owner_resource_id,purpose,state,revision,created_at,released_at";
    private static final String H="id,tenant_id,object_id,reason_sha256,placed_by,state,revision,placed_at,released_by,released_at";
    private static final String D="id,tenant_id,object_id,state,claim_token,claim_owner,lease_until,fencing_token,safe_error_code,revision,created_at,updated_at,completed_at";
    private final JdbcTemplate j;
    private final TransactionTemplate tx;
    public PostgresArtifactObjectRepository(JdbcTemplate j,PlatformTransactionManager manager){
        this.j=j;
        this.tx=new TransactionTemplate(manager);
    }
    public Instant currentTime(){
        return j.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
    }
    public void insertStaging(ArtifactObjectStagingSession v){
        j.update("INSERT INTO platform_artifact_object_staging(id,tenant_id,requested_by,request_id,expected_sha256,expected_bytes,media_type,encryption_reference,staging_reference,state,published_object_id,revision,expires_at,created_at,updated_at) VALUES(CAST(? AS UUID),?,?,?,?,?,?,?,?,?,CAST(? AS UUID),?,?,?,?)",v.id(),v.tenantId(),v.requestedBy(),v.requestId(),v.expectedSha256(),v.expectedBytes(),v.mediaType(),v.encryptionReference(),v.stagingReference(),v.state().name(),v.publishedObjectId(),v.revision(),ts(v.expiresAt()),ts(v.createdAt()),ts(v.updatedAt()));
    }
    @Override public void insertStaging(ArtifactObjectStagingSession v,StagingQuota q){
        tx.executeWithoutResult(status->{
            lock("artifact-capacity-tenant|"+v.tenantId());lock("artifact-staging-user|"+v.tenantId()+"|"+v.requestedBy());QuotaUsage tenant=stagingUsage(v.tenantId(),null);QuotaUsage user=stagingUsage(v.tenantId(),v.requestedBy());Number stored=j.queryForObject("SELECT COALESCE(SUM(byte_size),0) FROM platform_artifact_objects WHERE tenant_id=? AND state<>'DELETED'",Number.class,v.tenantId());if(user.sessions()>=q.maximumSessionsPerUser()||exceeds(user.bytes(),v.expectedBytes(),q.maximumBytesPerUser()))throw new ArtifactObjectQuotaExceededException(ArtifactObjectQuotaExceededException.Limit.USER_STAGING);if(tenant.sessions()>=q.maximumSessionsPerTenant()||exceeds(tenant.bytes(),v.expectedBytes(),q.maximumBytesPerTenant()))throw new ArtifactObjectQuotaExceededException(ArtifactObjectQuotaExceededException.Limit.TENANT_STAGING);if(exceeds(stored.longValue(),tenant.bytes(),q.maximumStoredBytesPerTenant())||exceeds(stored.longValue()+tenant.bytes(),v.expectedBytes(),q.maximumStoredBytesPerTenant()))throw new ArtifactObjectQuotaExceededException(ArtifactObjectQuotaExceededException.Limit.TENANT_STORAGE);insertStaging(v);
        });
    }
    public Optional<ArtifactObjectStagingSession> findStaging(String t,String id){
        return j.query("SELECT "+S+" FROM platform_artifact_object_staging WHERE tenant_id=? AND id=CAST(? AS UUID)",this::staging,t,id).stream().findFirst();
    }
    public Optional<ArtifactObjectStagingSession> findStagingByRequest(String t,String a,String r){
        return j.query("SELECT "+S+" FROM platform_artifact_object_staging WHERE tenant_id=? AND requested_by=? AND request_id=?",this::staging,t,a,r).stream().findFirst();
    }
    public Optional<ArtifactObjectStagingSession> updateStaging(ArtifactObjectStagingSession v,long r){
        return j.query("UPDATE platform_artifact_object_staging SET staging_reference=?,state=?,published_object_id=CAST(? AS UUID),revision=revision+1,updated_at=? WHERE tenant_id=? AND id=CAST(? AS UUID) AND revision=? RETURNING "+S,this::staging,v.stagingReference(),v.state().name(),v.publishedObjectId(),ts(v.updatedAt()),v.tenantId(),v.id(),r).stream().findFirst();
    }
    public PublishResult publish(String tenant, String id, long revision, ManagedArtifactObject proposed,
    Instant at, long maximumStoredBytesPerTenant) {
        return tx.execute(status -> {
            var current = j.query("SELECT " + S + " FROM platform_artifact_object_staging WHERE tenant_id=? AND id=CAST(? AS UUID) FOR UPDATE",
            this::staging, tenant, id).stream().findFirst().orElseThrow();
            if (current.revision() != revision || current.state() != ArtifactObjectStagingSession.State.VERIFIED)
            throw new IllegalStateException("Artifact staging publish conflict");
            lock("artifact-capacity-tenant|" + tenant);
            Number stored = j.queryForObject("SELECT COALESCE(SUM(byte_size),0) FROM platform_artifact_objects WHERE tenant_id=? AND state<>'DELETED'",
            Number.class, tenant);
            if (exceeds(stored.longValue(), proposed.byteSize(), maximumStoredBytesPerTenant))
            throw new ArtifactObjectQuotaExceededException(ArtifactObjectQuotaExceededException.Limit.TENANT_STORAGE);
            insertObject(proposed);
            var published = current.publish(proposed.id(), at);
            var saved = updateStaging(published, current.revision()).orElseThrow();
            return new PublishResult(saved, proposed, false, null);
        });
    }
    public Optional<ManagedArtifactObject> findObject(String t,String id){
        return j.query("SELECT "+O+" FROM platform_artifact_objects WHERE tenant_id=? AND id=CAST(? AS UUID)",this::object,t,id).stream().findFirst();
    }
    public Optional<ManagedArtifactObject> updateObject(ManagedArtifactObject v,long r){
        return j.query("UPDATE platform_artifact_objects SET state=?,revision=revision+1,updated_at=?,deleted_at=? WHERE tenant_id=? AND id=CAST(? AS UUID) AND revision=? RETURNING "+O,this::object,v.state().name(),ts(v.updatedAt()),ts(v.deletedAt()),v.tenantId(),v.id(),r).stream().findFirst();
    }
    public void insertReference(ArtifactObjectReference v) {
        tx.executeWithoutResult(status -> {
            requireReady(v.tenantId(), v.objectId());
            j.update("INSERT INTO platform_artifact_object_references(id,tenant_id,object_id,owner_type,owner_resource_id,purpose,state,revision,created_at,released_at) VALUES(CAST(? AS UUID),?,CAST(? AS UUID),?,?,?,?,?,?,?)",
            v.id(), v.tenantId(), v.objectId(), v.ownerType().name(), v.ownerResourceId(), v.purpose(), v.state().name(), v.revision(), ts(v.createdAt()), ts(v.releasedAt()));
        });
    }
    public Optional<ArtifactObjectReference> findReference(String t,String id){
        return j.query("SELECT "+R+" FROM platform_artifact_object_references WHERE tenant_id=? AND id=CAST(? AS UUID)",this::reference,t,id).stream().findFirst();
    }
    public Optional<ArtifactObjectReference> updateReference(ArtifactObjectReference v,long r){
        return j.query("UPDATE platform_artifact_object_references SET state=?,revision=revision+1,released_at=? WHERE tenant_id=? AND id=CAST(? AS UUID) AND revision=? RETURNING "+R,this::reference,v.state().name(),ts(v.releasedAt()),v.tenantId(),v.id(),r).stream().findFirst();
    }
    public List<ArtifactObjectReference> references(String t,String o){
        return j.query("SELECT "+R+" FROM platform_artifact_object_references WHERE tenant_id=? AND object_id=CAST(? AS UUID) ORDER BY created_at,id",this::reference,t,o);
    }
    public void insertHold(ArtifactObjectLegalHold v) {
        tx.executeWithoutResult(status -> {
            requireReady(v.tenantId(), v.objectId());
            j.update("INSERT INTO platform_artifact_object_legal_holds(id,tenant_id,object_id,reason_sha256,placed_by,state,revision,placed_at,released_by,released_at) VALUES(CAST(? AS UUID),?,CAST(? AS UUID),?,?,?,?,?,?,?)",
            v.id(), v.tenantId(), v.objectId(), v.reasonSha256(), v.placedBy(), v.state().name(), v.revision(), ts(v.placedAt()), v.releasedBy(), ts(v.releasedAt()));
        });
    }
    public Optional<ArtifactObjectLegalHold> findHold(String t,String id){
        return j.query("SELECT "+H+" FROM platform_artifact_object_legal_holds WHERE tenant_id=? AND id=CAST(? AS UUID)",this::hold,t,id).stream().findFirst();
    }
    public Optional<ArtifactObjectLegalHold> updateHold(ArtifactObjectLegalHold v,long r){
        return j.query("UPDATE platform_artifact_object_legal_holds SET state=?,revision=revision+1,released_by=?,released_at=? WHERE tenant_id=? AND id=CAST(? AS UUID) AND revision=? RETURNING "+H,this::hold,v.state().name(),v.releasedBy(),ts(v.releasedAt()),v.tenantId(),v.id(),r).stream().findFirst();
    }
    public List<ArtifactObjectLegalHold> holds(String t,String o){
        return j.query("SELECT "+H+" FROM platform_artifact_object_legal_holds WHERE tenant_id=? AND object_id=CAST(? AS UUID) ORDER BY placed_at,id",this::hold,t,o);
    }
    public void insertDeletion(ArtifactObjectDeletionJob v){
        j.update("INSERT INTO platform_artifact_object_deletions(id,tenant_id,object_id,state,claim_token,claim_owner,lease_until,fencing_token,safe_error_code,revision,created_at,updated_at,completed_at) VALUES(CAST(? AS UUID),?,CAST(? AS UUID),?,CAST(? AS UUID),?,?,?,?,?,?,?,?)",v.id(),v.tenantId(),v.objectId(),v.state().name(),v.claimToken(),v.claimOwner(),ts(v.leaseUntil()),v.fencingToken(),v.safeErrorCode(),v.revision(),ts(v.createdAt()),ts(v.updatedAt()),ts(v.completedAt()));
    }
    public Optional<ArtifactObjectDeletionJob> scheduleDeletion(ManagedArtifactObject proposed, long expected, ArtifactObjectDeletionJob job) {
        return tx.execute(status -> {
            var object = lockObject(proposed.tenantId(), proposed.id());
            if (object.revision() != expected || object.state() != ManagedArtifactObject.State.READY)
            return Optional.empty();
            var now = currentTime();
            if (hasBlockers(object.tenantId(), object.id()) || object.retention().retainUntil().isAfter(now))
            return Optional.empty();
            var pending = object.requestDeletion(0, false, now);
            if (updateObject(pending, expected).isEmpty()) return Optional.empty();
            insertDeletion(job);
            return Optional.of(job);
        });
    }
    public Optional<ArtifactObjectDeletionJob> findDeletion(String t,String o){
        return j.query("SELECT "+D+" FROM platform_artifact_object_deletions WHERE tenant_id=? AND object_id=CAST(? AS UUID)",this::deletion,t,o).stream().findFirst();
    }
    public Optional<ArtifactObjectDeletionJob> claimDeletion(String worker, String token, int seconds) {
        return tx.execute(status -> {
            var claim = j.query("WITH candidate AS(SELECT id FROM platform_artifact_object_deletions WHERE state='PENDING' ORDER BY created_at,id LIMIT 1 FOR UPDATE SKIP LOCKED) UPDATE platform_artifact_object_deletions d SET state='CLAIMED',claim_token=CAST(? AS UUID),claim_owner=?,lease_until=clock_timestamp()+(?*interval '1 second'),fencing_token=fencing_token+1,revision=revision+1,updated_at=clock_timestamp() FROM candidate WHERE d.id=candidate.id RETURNING d.*",
            this::deletion, token, worker, seconds).stream().findFirst();
            if (claim.isEmpty()) return Optional.empty();
            var job = claim.get();
            var object = lockObject(job.tenantId(), job.objectId());
            if (object.state() != ManagedArtifactObject.State.DELETE_PENDING || hasBlockers(job.tenantId(), job.objectId())) {
                var blocked = object.state() == ManagedArtifactObject.State.DELETE_PENDING ? object.blockDeletion(currentTime()) : object;
                finishDeletion(job.tenantId(), job.id(), job.revision(), job.claimToken(), job.fencingToken(),
                blocked, object.revision(), ArtifactObjectDeletionJob.State.BLOCKED,
                "ARTIFACT_DELETE_PERMISSION_BLOCKED", currentTime()).orElseThrow();
                return Optional.empty();
            }
            return claim;
        });
    }
    public Optional<ArtifactObjectDeletionJob> finishDeletion(String t,String id,long revision,String token,long fence,ManagedArtifactObject object,long objectRevision,ArtifactObjectDeletionJob.State state,String code,Instant at){
        try{
            UUID.fromString(token);
        }
        catch(RuntimeException malformed){
            return Optional.empty();
        }
        if(!List.of(ArtifactObjectDeletionJob.State.BLOCKED,ArtifactObjectDeletionJob.State.COMPLETED).contains(state))throw new IllegalArgumentException("Artifact deletion terminal state is invalid");
        return tx.execute(x->{
            if(updateObject(object,objectRevision).isEmpty())return Optional.empty();var done=j.query("UPDATE platform_artifact_object_deletions SET state=?,claim_token=NULL,claim_owner=NULL,lease_until=NULL,safe_error_code=?,revision=revision+1,updated_at=?,completed_at=? WHERE tenant_id=? AND id=CAST(? AS UUID) AND state='CLAIMED' AND revision=? AND claim_token=CAST(? AS UUID) AND fencing_token=? RETURNING "+D,this::deletion,state.name(),code,ts(at),state==ArtifactObjectDeletionJob.State.COMPLETED?ts(at):null,t,id,revision,token,fence).stream().findFirst();if(done.isEmpty())x.setRollbackOnly();return done;
        });
    }
    public List<ArtifactObjectStagingSession> findExpiredStaging(Instant at,int limit){
        return j.query("SELECT "+S+" FROM platform_artifact_object_staging WHERE expires_at<=? AND state IN('OPEN','UPLOADING','VERIFIED','UNKNOWN') ORDER BY expires_at,id LIMIT ?",this::staging,ts(at),limit);
    }
    public Optional<ArtifactObjectDeletionJob> recoverExpiredDeletion(Instant at){
        return tx.execute(x->{
            var claimed=j.query("SELECT "+D+" FROM platform_artifact_object_deletions WHERE state='CLAIMED' AND lease_until<=? ORDER BY lease_until,id LIMIT 1 FOR UPDATE SKIP LOCKED",this::deletion,ts(at)).stream().findFirst();if(claimed.isEmpty())return Optional.empty();var job=claimed.get();var object=j.query("SELECT "+O+" FROM platform_artifact_objects WHERE tenant_id=? AND id=CAST(? AS UUID) FOR UPDATE",this::object,job.tenantId(),job.objectId()).stream().findFirst().orElseThrow();ManagedArtifactObject blocked=object.state()==ManagedArtifactObject.State.DELETE_PENDING?object.blockDeletion(at):object;return finishDeletion(job.tenantId(),job.id(),job.revision(),job.claimToken(),job.fencingToken(),blocked,object.revision(),ArtifactObjectDeletionJob.State.BLOCKED,"ARTIFACT_DELETE_OUTCOME_UNKNOWN",at);
        });
    }
    public Optional<ArtifactObjectDeletionJob> retryBlockedDeletion(String tenant,String objectId,Instant at){
        return tx.execute(x->{
            var job=j.query("SELECT "+D+" FROM platform_artifact_object_deletions WHERE tenant_id=? AND object_id=CAST(? AS UUID) FOR UPDATE",this::deletion,tenant,objectId).stream().findFirst();var object=j.query("SELECT "+O+" FROM platform_artifact_objects WHERE tenant_id=? AND id=CAST(? AS UUID) FOR UPDATE",this::object,tenant,objectId).stream().findFirst();if(job.isEmpty()||object.isEmpty()||job.get().state()!=ArtifactObjectDeletionJob.State.BLOCKED||object.get().state()!=ManagedArtifactObject.State.BLOCKED)return Optional.empty();Boolean blocked=j.queryForObject("SELECT EXISTS(SELECT 1 FROM platform_artifact_object_references WHERE tenant_id=? AND object_id=CAST(? AS UUID) AND state='ACTIVE') OR EXISTS(SELECT 1 FROM platform_artifact_object_legal_holds WHERE tenant_id=? AND object_id=CAST(? AS UUID) AND state='ACTIVE')",Boolean.class,tenant,objectId,tenant,objectId);if(Boolean.TRUE.equals(blocked))return Optional.empty();if(updateObject(object.get().retryDeletion(at),object.get().revision()).isEmpty()){
                x.setRollbackOnly();return Optional.empty();
            }
            var retried=job.get().retry(at);var saved=j.query("UPDATE platform_artifact_object_deletions SET state='PENDING',claim_token=NULL,claim_owner=NULL,lease_until=NULL,safe_error_code=NULL,revision=revision+1,updated_at=?,completed_at=NULL WHERE tenant_id=? AND id=CAST(? AS UUID) AND state='BLOCKED' AND revision=? RETURNING "+D,this::deletion,ts(at),tenant,retried.id(),job.get().revision()).stream().findFirst();if(saved.isEmpty())x.setRollbackOnly();return saved;
        });
    }
    private ManagedArtifactObject lockObject(String tenant, String id) {
        return j.query("SELECT " + O + " FROM platform_artifact_objects WHERE tenant_id=? AND id=CAST(? AS UUID) FOR UPDATE",
        this::object, tenant, id).stream().findFirst().orElseThrow();
    }
    private void requireReady(String tenant, String id) {
        if (lockObject(tenant, id).state() != ManagedArtifactObject.State.READY)
        throw new ArtifactObjectLifecycleConflictException();
    }
    private boolean hasBlockers(String tenant, String id) {
        return Boolean.TRUE.equals(j.queryForObject("SELECT EXISTS(SELECT 1 FROM platform_artifact_object_references WHERE tenant_id=? AND object_id=CAST(? AS UUID) AND state='ACTIVE') OR EXISTS(SELECT 1 FROM platform_artifact_object_legal_holds WHERE tenant_id=? AND object_id=CAST(? AS UUID) AND state='ACTIVE')",
        Boolean.class, tenant, id, tenant, id));
    }
    private void lock(String key){
        j.query("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",rs->{
        },key);
    }
    private QuotaUsage stagingUsage(String tenant,String user){
        String predicate=user==null?"":" AND requested_by=?";
        Object[] arguments=user==null?new Object[]{
            tenant
        }
        :new Object[]{
            tenant,user
        };
        return j.query("SELECT count(*) session_count,COALESCE(SUM(expected_bytes),0) byte_count FROM platform_artifact_object_staging WHERE tenant_id=?"+predicate+" AND state IN('OPEN','UPLOADING','VERIFIED','UNKNOWN')",(rs,row)->new QuotaUsage(rs.getLong("session_count"),rs.getLong("byte_count")),arguments).getFirst();
    }
    private static boolean exceeds(long current,long added,long maximum){
        return current>maximum-added;
    }
    private void insertObject(ManagedArtifactObject v){
        j.update("INSERT INTO platform_artifact_objects(id,tenant_id,created_by,content_sha256,byte_size,media_type,storage_reference,encryption_reference,retain_until,delete_when_unreferenced,state,revision,created_at,updated_at,deleted_at) VALUES(CAST(? AS UUID),?,?,?,?,?,?,?,?,?,?,?,?,?,?)",v.id(),v.tenantId(),v.createdBy(),v.contentSha256(),v.byteSize(),v.mediaType(),v.storageReference(),v.encryptionReference(),ts(v.retention().retainUntil()),v.retention().deleteWhenUnreferenced(),v.state().name(),v.revision(),ts(v.createdAt()),ts(v.updatedAt()),ts(v.deletedAt()));
    }
    private ArtifactObjectStagingSession staging(ResultSet r,int n)throws SQLException{
        return new ArtifactObjectStagingSession(r.getString("id"),r.getString("tenant_id"),r.getString("requested_by"),r.getString("request_id"),r.getString("expected_sha256"),r.getLong("expected_bytes"),r.getString("media_type"),r.getString("encryption_reference"),r.getString("staging_reference"),ArtifactObjectStagingSession.State.valueOf(r.getString("state")),r.getString("published_object_id"),r.getLong("revision"),r.getTimestamp("expires_at").toInstant(),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());
    }
    private ManagedArtifactObject object(ResultSet r,int n)throws SQLException{
        return new ManagedArtifactObject(r.getString("id"),r.getString("tenant_id"),r.getString("created_by"),r.getString("content_sha256"),r.getLong("byte_size"),r.getString("media_type"),r.getString("storage_reference"),r.getString("encryption_reference"),new ManagedArtifactObject.Retention(r.getTimestamp("retain_until").toInstant(),r.getBoolean("delete_when_unreferenced")),ManagedArtifactObject.State.valueOf(r.getString("state")),r.getLong("revision"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),in(r.getTimestamp("deleted_at")));
    }
    private ArtifactObjectReference reference(ResultSet r,int n)throws SQLException{
        return new ArtifactObjectReference(r.getString("id"),r.getString("tenant_id"),r.getString("object_id"),ArtifactObjectReference.OwnerType.valueOf(r.getString("owner_type")),r.getString("owner_resource_id"),r.getString("purpose"),ArtifactObjectReference.State.valueOf(r.getString("state")),r.getLong("revision"),r.getTimestamp("created_at").toInstant(),in(r.getTimestamp("released_at")));
    }
    private ArtifactObjectLegalHold hold(ResultSet r,int n)throws SQLException{
        return new ArtifactObjectLegalHold(r.getString("id"),r.getString("tenant_id"),r.getString("object_id"),r.getString("reason_sha256"),r.getString("placed_by"),ArtifactObjectLegalHold.State.valueOf(r.getString("state")),r.getLong("revision"),r.getTimestamp("placed_at").toInstant(),r.getString("released_by"),in(r.getTimestamp("released_at")));
    }
    private ArtifactObjectDeletionJob deletion(ResultSet r,int n)throws SQLException{
        return new ArtifactObjectDeletionJob(r.getString("id"),r.getString("tenant_id"),r.getString("object_id"),ArtifactObjectDeletionJob.State.valueOf(r.getString("state")),r.getString("claim_token"),r.getString("claim_owner"),in(r.getTimestamp("lease_until")),r.getLong("fencing_token"),r.getString("safe_error_code"),r.getLong("revision"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant(),in(r.getTimestamp("completed_at")));
    }
    private static Timestamp ts(Instant v){
        return v==null?null:Timestamp.from(v);
    }
    private static Instant in(Timestamp v){
        return v==null?null:v.toInstant();
    }
    private record QuotaUsage(long sessions,long bytes){
    }
}
