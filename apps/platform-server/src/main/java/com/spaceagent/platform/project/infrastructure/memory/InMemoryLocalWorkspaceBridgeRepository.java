package com.spaceagent.platform.project.infrastructure.memory;

import com.spaceagent.platform.project.domain.LocalWorkspaceBridge;
import com.spaceagent.platform.project.domain.LocalWorkspaceBridgeRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryLocalWorkspaceBridgeRepository implements LocalWorkspaceBridgeRepository {
    private final Map<String, LocalWorkspaceBridge> values = new ConcurrentHashMap<>();
    public void save(LocalWorkspaceBridge value) { values.put(value.id(), value); }
    public Optional<LocalWorkspaceBridge> findById(String id) { return Optional.ofNullable(values.get(id)); }
    public Optional<LocalWorkspaceBridge> findByTokenHash(String hash) {
        return values.values().stream().filter(v -> hash.equals(v.tokenHash())).findFirst();
    }
    public List<LocalWorkspaceBridge> findByOwner(String tenantId, String ownerId) {
        return values.values().stream().filter(v -> tenantId.equals(v.tenantId()))
                .filter(v -> ownerId.equals(v.ownerId()))
                .sorted(Comparator.comparing(LocalWorkspaceBridge::createdAt)).toList();
    }
    public boolean exists(String tenantId, String ownerId, String deviceId, String rootHandle) {
        return values.values().stream().anyMatch(v -> tenantId.equals(v.tenantId())
                && ownerId.equals(v.ownerId()) && deviceId.equals(v.deviceId())
                && rootHandle.equals(v.rootHandle())
                && v.state() == com.spaceagent.platform.project.domain.LocalWorkspaceBridgeState.ACTIVE);
    }
}
