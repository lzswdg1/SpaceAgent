package com.spaceagent.platform.project.application;

import com.spaceagent.platform.project.domain.*;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LocalProjectMaterializationExpirySweeper {
    private final ProjectLocalMaterializationSessionRepository sessions;
    private final ProjectLocalMaterializationChunkStaging staging;
    private final TimeProvider time;
    public LocalProjectMaterializationExpirySweeper(ProjectLocalMaterializationSessionRepository sessions,
            ProjectLocalMaterializationChunkStaging staging,TimeProvider time){this.sessions=sessions;this.staging=staging;this.time=time;}
    @Scheduled(fixedDelayString="${platform.local-materialization.cleanup-delay-ms:60000}")
    public int sweep(){var now=time.now();int changed=0;for(var current:sessions.findActiveExpiredBefore(now,100)){
        staging.cleanupSession(current.id());var expired=current.expire(now);
        if(sessions.update(expired,current.revision(),current.state()))changed++;}return changed;}
}
