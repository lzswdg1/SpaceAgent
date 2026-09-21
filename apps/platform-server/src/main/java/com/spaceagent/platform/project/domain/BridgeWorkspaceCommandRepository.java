package com.spaceagent.platform.project.domain;
import java.time.Instant; import java.util.List; import java.util.Optional;
public interface BridgeWorkspaceCommandRepository {
    void save(BridgeWorkspaceCommand command);
    Optional<BridgeWorkspaceCommand> findById(String id);
    List<BridgeWorkspaceCommand> findPendingByBridgeId(String bridgeId);
    boolean complete(String id, BridgeWorkspaceCommandState state, Instant at);
}
