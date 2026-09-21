package com.spaceagent.platform.artifact.application;

import com.spaceagent.platform.artifact.api.ArtifactObjectMaintenanceApplicationApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="platform",name="persistence",havingValue="postgres")
public class ArtifactObjectMaintenanceWorker {
    private final ArtifactObjectMaintenanceApplicationApi maintenance;private final String worker="artifact-object-"+java.util.UUID.randomUUID();
    public ArtifactObjectMaintenanceWorker(ArtifactObjectMaintenanceApplicationApi maintenance){this.maintenance=maintenance;}
    @Scheduled(fixedDelayString="${platform.artifact-object.maintenance-delay-ms:60000}") public void poll(){try{maintenance.recoverExpiredDeletion();maintenance.sweepExpiredStaging(25);maintenance.runDeletionOnce(worker,120);}catch(RuntimeException ignored){/* Durable rows remain pending or blocked for bounded recovery. */}}
}
