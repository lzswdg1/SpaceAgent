package com.spaceagent.platform.project.application;

import com.spaceagent.platform.project.api.LocalProjectMaterializationApplicationApi;
import com.spaceagent.platform.project.api.LocalWorkspaceBridgeApplicationApi;
import com.spaceagent.platform.project.api.HeartbeatLocalWorkspaceBridgeCommand;
import com.spaceagent.platform.project.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LocalProjectMaterializationApplicationService implements LocalProjectMaterializationApplicationApi {
    private final ProjectLocalMaterializationSessionRepository sessions;
    private final ProjectLocalMaterializationChunkRepository chunks;
    private final SourceRepositoryRepository sources;
    private final ProjectLocalMaterializationSnapshotGateway snapshots;
    private final ProjectAccessPolicy access;
    private final IdGenerator ids;
    private final TimeProvider time;
    private final ProjectLocalMaterializationChunkStaging staging;
    private final LocalWorkspaceBridgeApplicationApi bridges;

    public LocalProjectMaterializationApplicationService(ProjectLocalMaterializationSessionRepository sessions,
            ProjectLocalMaterializationChunkRepository chunks, SourceRepositoryRepository sources,
            ProjectLocalMaterializationSnapshotGateway snapshots, ProjectAccessPolicy access,
            IdGenerator ids, TimeProvider time) {
        this(sessions,chunks,sources,snapshots,access,ids,time,null,null);
    }

    public LocalProjectMaterializationApplicationService(ProjectLocalMaterializationSessionRepository sessions,
            ProjectLocalMaterializationChunkRepository chunks, SourceRepositoryRepository sources,
            ProjectLocalMaterializationSnapshotGateway snapshots, ProjectAccessPolicy access,
            IdGenerator ids, TimeProvider time, ProjectLocalMaterializationChunkStaging staging) {
        this(sessions,chunks,sources,snapshots,access,ids,time,staging,null);
    }

    @Autowired
    public LocalProjectMaterializationApplicationService(ProjectLocalMaterializationSessionRepository sessions,
            ProjectLocalMaterializationChunkRepository chunks, SourceRepositoryRepository sources,
            ProjectLocalMaterializationSnapshotGateway snapshots, ProjectAccessPolicy access,
            IdGenerator ids, TimeProvider time, ProjectLocalMaterializationChunkStaging staging,
            LocalWorkspaceBridgeApplicationApi bridges) {
        this.sessions=sessions;this.chunks=chunks;this.sources=sources;this.snapshots=snapshots;
        this.access=access;this.ids=ids;this.time=time;this.staging=staging;this.bridges=bridges;
    }

    @Override public SessionResult start(StartCommand c){requireProject(c.tenantId(),c.ownerUserId(),c.projectId());
        var existing=sessions.findByRequest(c.tenantId(),c.ownerUserId(),c.projectId(),c.bridgeId(),c.requestId()).orElse(null);
        if(existing!=null){if(!existing.bridgeDeviceId().equals(c.bridgeDeviceId())||!existing.bridgeRootHandle().equals(c.bridgeRootHandle()))throw conflict("MATERIALIZATION_START_CONFLICT");return session(existing);}
        Instant now=time.now();var created=ProjectLocalMaterializationSession.open(ids.nextId(),c.tenantId(),c.ownerUserId(),c.projectId(),c.bridgeId(),c.bridgeDeviceId(),c.bridgeRootHandle(),c.requestId(),now.plus(15,ChronoUnit.MINUTES),now);
        sessions.insert(created);return session(created);}

    @Override public SessionResult declareManifest(ManifestCommand c){requireProject(c.tenantId(),c.ownerUserId(),c.projectId());var current=requireAuthorizedSession(c.tenantId(),c.ownerUserId(),c.projectId(),c.sessionId(),c.bridgeToken());
        if(current.state()==ProjectLocalMaterializationSessionState.UPLOADING&&c.manifestSha256().equals(current.manifestSha256()))return session(current);
        var next=current.beginUpload(c.manifestSha256(),time.now());if(!sessions.update(next,current.revision(),current.state()))throw conflict("MATERIALIZATION_REVISION_CONFLICT");return session(next);}

    @Override public ChunkResult stageChunk(ChunkCommand c,InputStream bytes){requireProject(c.tenantId(),c.ownerUserId(),c.projectId());var session=requireAuthorizedSession(c.tenantId(),c.ownerUserId(),c.projectId(),c.sessionId(),c.bridgeToken());
        if(session.state()!=ProjectLocalMaterializationSessionState.UPLOADING||staging==null)throw conflict("MATERIALIZATION_SESSION_STATE_CONFLICT");
        String key=stagingKey(session.id(),c.requestId());staging.stage(session.id(),key,c.contentLength(),c.contentSha256(),bytes);
        var chunk=ProjectLocalMaterializationChunk.stored(ids.nextId(),session.id(),c.requestId(),c.relativePath(),c.offset(),c.contentLength(),c.contentSha256(),key,time.now());
        var stored=chunks.insertOrGet(chunk);return new ChunkResult(stored.requestId(),stored.contentSha256(),stored.contentLength());}

    @Override
    @Transactional
    public Result finalizeSnapshot(FinalizeCommand command) {
        Project project=requireProject(command.tenantId(),command.ownerUserId(),command.projectId());
        ProjectLocalMaterializationSession session=requireAuthorizedSession(command.tenantId(),command.ownerUserId(),
                command.projectId(),command.sessionId(),command.bridgeToken());
        SourceRepository existing=sources.findByMaterializationSessionId(session.id()).orElse(null);
        if(existing!=null){
            if(!existing.finalizeRequestId().equals(command.requestId())||!existing.manifestSha256().equals(command.manifestSha256()))
                throw conflict("MATERIALIZATION_FINALIZE_CONFLICT");
            if(existing.state()!=SourceRepositoryState.READY||session.state()!=ProjectLocalMaterializationSessionState.MATERIALIZED)
                throw conflict("MATERIALIZATION_FINALIZE_INCOMPLETE");
            return result(session,existing);
        }
        if(session.isTerminal()||session.state()==ProjectLocalMaterializationSessionState.BLOCKED)
            throw conflict("MATERIALIZATION_SESSION_STATE_CONFLICT");
        var validated=ProjectLocalMaterializationManifestValidator.validate(command.manifest(),chunks.findBySessionId(session.id()));
        if(!validated.manifestSha256().equals(command.manifestSha256())) throw conflict("MATERIALIZATION_MANIFEST_MISMATCH");
        Instant now=time.now();
        if(session.state()==ProjectLocalMaterializationSessionState.OPEN){
            var uploading=session.beginUpload(validated.manifestSha256(),now);
            if(!sessions.update(uploading,session.revision(),session.state()))throw conflict("MATERIALIZATION_REVISION_CONFLICT");
            session=uploading;
        }
        if(session.state()!=ProjectLocalMaterializationSessionState.UPLOADING||!session.manifestSha256().equals(validated.manifestSha256()))
            throw conflict("MATERIALIZATION_SESSION_STATE_CONFLICT");
        String sourceId=ids.nextId();
        SourceRepository source=new SourceRepository(sourceId,project.id(),project.tenantId(),null,null,null,
                "snapshot:"+session.id(),"Managed snapshot "+session.id().substring(0,8),null,null,"snapshot",
                SourceRepositoryType.MANAGED_SNAPSHOT,SourceRepositoryState.PROVISIONING,SourceRepositoryVisibility.PRIVATE,
                command.ownerUserId(),now,now,session.id(),"sources/"+session.id(),validated.manifestSha256(),
                validated.manifestSha256(),command.requestId());
        sources.save(source);
        var published=snapshots.publish(session,command.manifest(),chunks.findBySessionId(session.id()));
        if(!published.snapshotRef().equals(source.snapshotRef())||!published.contentSha256().equals(source.contentSha256()))
            throw conflict("MATERIALIZATION_PUBLICATION_EVIDENCE_MISMATCH");
        var verified=session.verify(time.now());
        if(!sessions.update(verified,session.revision(),session.state()))throw conflict("MATERIALIZATION_REVISION_CONFLICT");
        var materialized=verified.materialize(time.now());
        if(!sessions.update(materialized,verified.revision(),verified.state()))throw conflict("MATERIALIZATION_REVISION_CONFLICT");
        SourceRepository ready=source.ready(time.now());sources.save(ready);return result(materialized,ready);
    }
    private static Result result(ProjectLocalMaterializationSession s,SourceRepository r){return new Result(s.id(),r.id(),r.snapshotRef(),r.manifestSha256(),r.contentSha256());}
    private Project requireProject(String tenant,String owner,String projectId){Project project=access.requireProject(tenant,owner,projectId);access.requireActive(project);access.requireRole(project,owner,ProjectRole::canModify);return project;}
    private ProjectLocalMaterializationSession requireSession(String tenant,String owner,String project,String id){return sessions.findById(tenant,owner,project,id).orElseThrow(()->conflict("MATERIALIZATION_SESSION_NOT_FOUND"));}
    private ProjectLocalMaterializationSession requireAuthorizedSession(String tenant,String owner,String project,String id,String token){var session=requireSession(tenant,owner,project,id);if(!time.now().isBefore(session.expiresAt()))throw conflict("MATERIALIZATION_SESSION_EXPIRED");if(bridges!=null){var bridge=bridges.heartbeat(new HeartbeatLocalWorkspaceBridgeCommand(tenant,owner,session.bridgeId(),token));if(!bridge.id().equals(session.bridgeId())||!bridge.deviceId().equals(session.bridgeDeviceId())||!bridge.rootHandle().equals(session.bridgeRootHandle()))throw conflict("MATERIALIZATION_BRIDGE_BINDING_MISMATCH");}return session;}
    private static SessionResult session(ProjectLocalMaterializationSession value){return new SessionResult(value.id(),value.state().name(),value.manifestSha256(),value.expiresAt(),value.revision());}
    private static String stagingKey(String sessionId,String requestId){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((sessionId+"\n"+requestId).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static BusinessException conflict(String code){return new BusinessException("Local materialization finalize conflict",HttpStatus.CONFLICT,code);}
}
