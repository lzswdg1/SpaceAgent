package com.spaceagent.platform.project.infrastructure;

import com.spaceagent.platform.project.domain.*;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Recovers committed PROVISIONING rows left by a process crash after the bounded Git window. */
@Component
public class WorkspaceProvisioningRecoveryWorker {
    private static final String SAFE_FAILURE = "WORKSPACE_PROVISIONING_INTERRUPTED";
    private final WorkspaceRepository workspaces;
    private final SourceRepositoryRepository sources;
    private final WorkspaceProvisioningGateway gateway;
    private final TimeProvider time;
    private final long staleSeconds;

    public WorkspaceProvisioningRecoveryWorker(
            WorkspaceRepository workspaces,
            SourceRepositoryRepository sources,
            WorkspaceProvisioningGateway gateway,
            TimeProvider time,
            WorkspaceProperties properties,
            @Value("${platform.workspace.provisioning-stale-seconds:300}") long staleSeconds) {
        this.workspaces = workspaces;
        this.sources = sources;
        this.gateway = gateway;
        this.time = time;
        this.staleSeconds = Math.max(
                properties.getGitTimeoutSeconds() + 60L, Math.max(180, staleSeconds));
    }

    @Scheduled(fixedDelayString =
            "${platform.workspace.provisioning-recovery-delay-ms:60000}")
    public void recoverStale() {
        Instant now = time.now();
        for (Workspace workspace : workspaces.findStaleProvisioning(
                now.minusSeconds(staleSeconds), 50)) {
            SourceRepository source = sources.findById(workspace.sourceRepositoryId()).orElse(null);
            if (source != null) {
                try {
                    gateway.cleanup(workspace, source);
                } catch (RuntimeException ignored) {
                    continue;
                }
            }
            workspaces.failProvisioning(
                    workspace.id(), workspace.revision(), SAFE_FAILURE, now);
        }
    }
}
