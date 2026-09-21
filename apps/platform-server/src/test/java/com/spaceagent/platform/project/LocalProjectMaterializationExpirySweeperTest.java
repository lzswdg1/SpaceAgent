package com.spaceagent.platform.project;

import com.spaceagent.platform.project.application.LocalProjectMaterializationExpirySweeper;
import com.spaceagent.platform.project.domain.*;
import com.spaceagent.platform.project.infrastructure.memory.InMemoryProjectLocalMaterializationSessionRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class LocalProjectMaterializationExpirySweeperTest {
    @Test void deletesPartialBytesBeforeMarkingExpired() {
        Instant now=Instant.parse("2026-09-08T02:00:00Z");var sessions=new InMemoryProjectLocalMaterializationSessionRepository();
        var value=ProjectLocalMaterializationSession.open(UUID.randomUUID().toString(),"tenant","owner",
                UUID.randomUUID().toString(),UUID.randomUUID().toString(),"device","root_12345678","request",
                now.minusSeconds(1),now.minusSeconds(60));sessions.insert(value);
        var staging=mock(ProjectLocalMaterializationChunkStaging.class);
        assertThat(new LocalProjectMaterializationExpirySweeper(sessions,staging,()->now).sweep()).isEqualTo(1);
        var expired=sessions.findById("tenant","owner",value.projectId(),value.id()).orElseThrow();
        assertThat(expired.state()).isEqualTo(ProjectLocalMaterializationSessionState.EXPIRED);
        var order=inOrder(staging);order.verify(staging).cleanupSession(value.id());
    }
}
