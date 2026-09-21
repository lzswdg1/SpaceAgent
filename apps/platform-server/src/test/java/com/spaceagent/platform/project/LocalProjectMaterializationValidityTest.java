package com.spaceagent.platform.project;

import com.spaceagent.platform.project.api.*;
import com.spaceagent.platform.project.application.LocalProjectMaterializationApplicationService;
import com.spaceagent.platform.project.application.ProjectAccessPolicy;
import com.spaceagent.platform.project.domain.*;
import com.spaceagent.shared.exception.BusinessException;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalProjectMaterializationValidityTest {
    private static final Instant NOW=Instant.parse("2026-09-09T00:00:00Z");

    @Test void expiredRevokedOrSubstitutedBridgeFailsBeforeByteEffect(){
        var sessions=mock(ProjectLocalMaterializationSessionRepository.class);var chunks=mock(ProjectLocalMaterializationChunkRepository.class);var sources=mock(SourceRepositoryRepository.class);var snapshots=mock(ProjectLocalMaterializationSnapshotGateway.class);var access=mock(ProjectAccessPolicy.class);var staging=mock(ProjectLocalMaterializationChunkStaging.class);var bridges=mock(LocalWorkspaceBridgeApplicationApi.class);var project=mock(Project.class);when(access.requireProject("tenant","owner","project")).thenReturn(project);
        var expired=session(NOW);when(sessions.findById("tenant","owner","project","session")).thenReturn(Optional.of(expired));var service=new LocalProjectMaterializationApplicationService(sessions,chunks,sources,snapshots,access,()->"id",()->NOW,staging,bridges);
        assertThatThrownBy(()->service.declareManifest(new LocalProjectMaterializationApplicationApi.ManifestCommand("tenant","owner","project","session","request","sha256:"+"a".repeat(64),"token"))).isInstanceOfSatisfying(BusinessException.class,e->org.assertj.core.api.Assertions.assertThat(e.getCode()).isEqualTo("MATERIALIZATION_SESSION_EXPIRED"));
        var active=session(NOW.plusSeconds(60));when(sessions.findById("tenant","owner","project","session")).thenReturn(Optional.of(active));when(bridges.heartbeat(any())).thenThrow(new BusinessException("revoked",org.springframework.http.HttpStatus.UNAUTHORIZED,"WORKSPACE_BRIDGE_AUTHENTICATION_FAILED"));
        assertThatThrownBy(()->service.stageChunk(chunk(),new ByteArrayInputStream(new byte[]{1}))).isInstanceOf(BusinessException.class);verifyNoInteractions(staging,snapshots);
        reset(bridges);when(bridges.heartbeat(any())).thenReturn(new LocalWorkspaceBridgeView("bridge","Bridge","other-device","root_12345678","prefix",LocalWorkspaceBridgeState.ACTIVE,NOW,NOW,NOW,null));
        assertThatThrownBy(()->service.stageChunk(chunk(),new ByteArrayInputStream(new byte[]{1}))).isInstanceOfSatisfying(BusinessException.class,e->org.assertj.core.api.Assertions.assertThat(e.getCode()).isEqualTo("MATERIALIZATION_BRIDGE_BINDING_MISMATCH"));verifyNoInteractions(staging,snapshots);
    }

    private static LocalProjectMaterializationApplicationApi.ChunkCommand chunk(){return new LocalProjectMaterializationApplicationApi.ChunkCommand("tenant","owner","project","session","chunk","a.txt",0,1,"sha256:"+"b".repeat(64),"token");}
    private static ProjectLocalMaterializationSession session(Instant expiry){return ProjectLocalMaterializationSession.open("session","tenant","owner","project","bridge","device","root_12345678","start",expiry,NOW.minusSeconds(1));}
}
