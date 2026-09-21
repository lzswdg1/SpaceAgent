package com.spaceagent.platform.project.domain;

import java.util.List;
import java.util.Optional;

public interface LocalWorkspaceBridgeRepository {

    void save(LocalWorkspaceBridge bridge);

    Optional<LocalWorkspaceBridge> findById(String id);

    Optional<LocalWorkspaceBridge> findByTokenHash(String tokenHash);

    List<LocalWorkspaceBridge> findByOwner(String tenantId, String ownerId);

    boolean exists(String tenantId, String ownerId, String deviceId, String rootHandle);
}
