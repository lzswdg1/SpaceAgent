package com.spaceagent.platform.runtime.domain;

import java.util.Optional;

public interface AgentRunConfigurationSnapshotRepository {

    AgentRunConfigurationSnapshot insertIfAbsent(AgentRunConfigurationSnapshot snapshot);

    Optional<AgentRunConfigurationSnapshot> findByRunId(
            String tenantId, String ownerUserId, String runId);
}
