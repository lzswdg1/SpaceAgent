package com.spaceagent.platform.project;

import com.spaceagent.platform.identity.api.IdentityOwnershipPort;
import com.spaceagent.platform.project.api.CreateProjectCommand;
import com.spaceagent.platform.project.api.LocalProjectMaterializationApplicationApi;
import com.spaceagent.platform.project.application.*;
import com.spaceagent.platform.project.domain.*;
import com.spaceagent.platform.project.infrastructure.*;
import com.spaceagent.platform.project.infrastructure.memory.*;
import com.spaceagent.shared.id.UuidGenerator;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class LocalProjectMaterializationApplicationServiceTest {
    private static final Instant NOW=Instant.parse("2026-09-08T00:00:00Z");
    @TempDir Path temp;
    private InMemorySourceRepositoryRepository sources;
    private InMemoryProjectDirectoryRepository directories;
    private InMemoryWorkspaceRepository workspaces;
    private InMemoryProjectLocalMaterializationSessionRepository sessions;
    private InMemoryProjectLocalMaterializationChunkRepository chunks;
    private FileSystemProjectLocalMaterializationChunkStaging staging;
    private String projectId;
    private LocalProjectMaterializationApplicationService service;
    private ProjectLocalMaterializationSession session;
    private ProjectLocalMaterializationManifestValidator.Manifest manifest;

    @BeforeEach void setUp() {
        var projects=new InMemoryProjectRepository();var memberships=new InMemoryProjectMembershipRepository();
        directories=new InMemoryProjectDirectoryRepository();workspaces=new InMemoryWorkspaceRepository();
        IdentityOwnershipPort identity=(tenant,user)->tenant.equals("tenant-1")&&user.equals("owner-1");
        var access=new ProjectAccessPolicy(projects,memberships,identity, (t, u, w) -> {});var ids=new UuidGenerator();
        projectId=new ProjectApplicationService(projects,memberships,access,ids,()->NOW,directories)
                .createProject(new CreateProjectCommand("tenant-1","owner-1","Snapshots",null)).id();
        sessions=new InMemoryProjectLocalMaterializationSessionRepository();
        chunks=new InMemoryProjectLocalMaterializationChunkRepository();sources=new InMemorySourceRepositoryRepository();
        WorkspaceProperties props=new WorkspaceProperties();props.setManagedRoot(temp.resolve("managed").toString());
        staging=new FileSystemProjectLocalMaterializationChunkStaging(props);
        byte[] body="hello".getBytes(StandardCharsets.UTF_8);String bodyHash=hash(body);
        session=ProjectLocalMaterializationSession.open(UUID.randomUUID().toString(),"tenant-1","owner-1",projectId,
                UUID.randomUUID().toString(),"device-1","root_12345678","open-1",NOW.plusSeconds(3600),NOW);
        sessions.insert(session);String key="b".repeat(64);staging.stage(session.id(),key,body.length,bodyHash,new ByteArrayInputStream(body));
        chunks.insertOrGet(ProjectLocalMaterializationChunk.stored(UUID.randomUUID().toString(),session.id(),"chunk-1",
                "src/App.txt",0,body.length,bodyHash,key,NOW));
        var entry=new ProjectLocalMaterializationManifestValidator.Entry("src/App.txt",
                ProjectLocalMaterializationManifestValidator.EntryKind.REGULAR_FILE,body.length,bodyHash);
        manifest=new ProjectLocalMaterializationManifestValidator.Manifest(
                ProjectLocalMaterializationManifestValidator.canonicalDigest(List.of(entry)),1,body.length,List.of(entry));
        service=new LocalProjectMaterializationApplicationService(sessions,chunks,sources,
                new FileSystemProjectLocalMaterializationSnapshotGateway(props,staging),access,ids,()->NOW.plusSeconds(2),staging);
    }

    @Test void publishesOneReadySnapshotAndReplaysWithoutBusinessBindings() {
        var command=new LocalProjectMaterializationApplicationApi.FinalizeCommand("tenant-1","owner-1",session.projectId(),
                session.id(),"finalize-1",manifest.manifestSha256(),manifest);
        var first=service.finalizeSnapshot(command);var replay=service.finalizeSnapshot(command);
        assertThat(replay).isEqualTo(first);
        assertThat(sources.findByMaterializationSessionId(session.id())).hasValueSatisfying(source->{
            assertThat(source.type()).isEqualTo(SourceRepositoryType.MANAGED_SNAPSHOT);
            assertThat(source.state()).isEqualTo(SourceRepositoryState.READY);
            assertThat(source.snapshotRef()).isEqualTo("sources/"+session.id()).doesNotContain(temp.toString());
            assertThat(source.workspaceBridgeId()).isNull();assertThat(source.localRootHandle()).isNull();
        });
        assertThat(directories.findByProjectId(session.projectId()))
                .noneMatch(directory -> first.sourceRepositoryId().equals(directory.sourceRepositoryId()));
        assertThat(workspaces.findByProjectId(session.projectId())).isEmpty();
        assertThatThrownBy(()->service.finalizeSnapshot(new LocalProjectMaterializationApplicationApi.FinalizeCommand(
                "tenant-1","owner-1",session.projectId(),session.id(),"changed",manifest.manifestSha256(),manifest)))
                .isInstanceOf(com.spaceagent.shared.exception.BusinessException.class);
    }

    @Test void startsDeclaresAndStagesWithStableReplay() {
        var started=service.start(new LocalProjectMaterializationApplicationApi.StartCommand("tenant-1","owner-1",
                projectId,UUID.randomUUID().toString(),"device-2","root_abcdefgh","start-2"));
        assertThat(service.start(new LocalProjectMaterializationApplicationApi.StartCommand("tenant-1","owner-1",
                projectId,sessions.findById("tenant-1","owner-1",projectId,started.sessionId()).orElseThrow().bridgeId(),
                "device-2","root_abcdefgh","start-2"))).isEqualTo(started);
        byte[] body="more".getBytes(StandardCharsets.UTF_8);String digest=hash(body);
        var declared=service.declareManifest(new LocalProjectMaterializationApplicationApi.ManifestCommand(
                "tenant-1","owner-1",projectId,started.sessionId(),"manifest-2","sha256:"+"c".repeat(64)));
        assertThat(declared.state()).isEqualTo("UPLOADING");
        var command=new LocalProjectMaterializationApplicationApi.ChunkCommand("tenant-1","owner-1",projectId,
                started.sessionId(),"chunk-2","file.txt",0,body.length,digest);
        assertThat(service.stageChunk(command,new ByteArrayInputStream(body)))
                .isEqualTo(service.stageChunk(command,new ByteArrayInputStream(body)));
    }

    private static String hash(byte[] value){try{return "sha256:"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}catch(Exception e){throw new IllegalStateException(e);}}
}
