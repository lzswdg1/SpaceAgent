package com.spaceagent.platform.inference.domain;

import java.util.List;
import java.util.Optional;

/** Persistence port for ModelPool and ordered member candidates. */
public interface ModelPoolRepository {

    void savePool(ModelPool pool);

    Optional<ModelPool> findPoolById(String poolId);

    List<ModelPool> findPoolsByTenantId(String tenantId);

    boolean existsByTenantAndName(String tenantId, String name);

    void saveMember(ModelPoolMember member);

    Optional<ModelPoolMember> findMemberById(String memberId);

    List<ModelPoolMember> findMembersByPoolId(String poolId);

    boolean removeMember(String poolId, String memberId);

    boolean isProviderReferenced(String providerId);

    boolean isProviderModelReferenced(String providerModelId);
}
