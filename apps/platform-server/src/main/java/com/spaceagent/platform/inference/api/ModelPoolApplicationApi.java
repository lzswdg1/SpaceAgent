package com.spaceagent.platform.inference.api;

import java.util.List;

public interface ModelPoolApplicationApi {

    ModelPoolView createPool(CreateModelPoolCommand command);

    List<ModelPoolView> listPools(String tenantId, String userId);

    ModelPoolView getPool(String tenantId, String userId, String poolId);

    List<ModelPoolMemberView> listMembers(String tenantId, String userId, String poolId);

    ModelPoolMemberView addMember(AddModelPoolMemberCommand command);

    void removeMember(RemoveModelPoolMemberCommand command);

    ModelPoolView activatePool(UpdateModelPoolStatusCommand command);

    ModelPoolView disablePool(UpdateModelPoolStatusCommand command);

    default ModelPoolResolutionView resolvePool(String tenantId, String userId, String poolId) {
        return resolvePool(tenantId,userId,poolId,poolId);
    }
    ModelPoolResolutionView resolvePool(String tenantId,String userId,String poolId,String routingKey);
}
