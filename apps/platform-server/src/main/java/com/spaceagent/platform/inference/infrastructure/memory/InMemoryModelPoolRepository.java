package com.spaceagent.platform.inference.infrastructure.memory;

import com.spaceagent.platform.inference.domain.ModelPool;
import com.spaceagent.platform.inference.domain.ModelPoolMember;
import com.spaceagent.platform.inference.domain.ModelPoolRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "memory", matchIfMissing = true)
public class InMemoryModelPoolRepository implements ModelPoolRepository {

    private final Map<String, ModelPool> pools = new ConcurrentHashMap<>();
    private final Map<String, ModelPoolMember> members = new ConcurrentHashMap<>();

    @Override
    public void savePool(ModelPool pool) {
        pools.put(pool.id(), pool);
    }

    @Override
    public Optional<ModelPool> findPoolById(String poolId) {
        return Optional.ofNullable(pools.get(poolId));
    }

    @Override
    public List<ModelPool> findPoolsByTenantId(String tenantId) {
        return pools.values().stream()
                .filter(pool -> tenantId.equals(pool.tenantId()))
                .sorted(Comparator.comparing(ModelPool::createdAt).thenComparing(ModelPool::id))
                .toList();
    }

    @Override
    public boolean existsByTenantAndName(String tenantId, String name) {
        return pools.values().stream()
                .anyMatch(pool -> tenantId.equals(pool.tenantId()) && name.equals(pool.name()));
    }

    @Override
    public void saveMember(ModelPoolMember member) {
        members.put(member.id(), member);
    }

    @Override
    public Optional<ModelPoolMember> findMemberById(String memberId) {
        return Optional.ofNullable(members.get(memberId));
    }

    @Override
    public List<ModelPoolMember> findMembersByPoolId(String poolId) {
        return members.values().stream()
                .filter(member -> poolId.equals(member.poolId()))
                .sorted(Comparator.comparingInt(ModelPoolMember::priority)
                        .thenComparing(ModelPoolMember::id))
                .toList();
    }

    @Override
    public boolean removeMember(String poolId, String memberId) {
        return findMemberById(memberId)
                .filter(member -> poolId.equals(member.poolId()))
                .map(member -> members.remove(member.id()) != null)
                .orElse(false);
    }

    @Override
    public boolean isProviderReferenced(String providerId) {
        return members.values().stream()
                .anyMatch(member -> providerId.equals(member.providerId()));
    }

    @Override
    public boolean isProviderModelReferenced(String providerModelId) {
        return members.values().stream()
                .anyMatch(member -> providerModelId.equals(member.providerModelId()));
    }
}
