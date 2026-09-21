package com.spaceagent.platform.identity.application;

import com.spaceagent.platform.identity.api.IdentityActivityApplicationApi;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import com.spaceagent.platform.identity.api.IdentityExecutionAuthorizationApi;
import com.spaceagent.platform.identity.domain.TenantMembershipStatus;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.identity.domain.TenantStatus;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityExecutionAuthorizationService implements IdentityExecutionAuthorizationApi {
    private final IdentityApplicationApi identity;
    private final IdentityActivityApplicationApi activity;

    public IdentityExecutionAuthorizationService(IdentityApplicationApi identity, IdentityActivityApplicationApi activity) {
        this.identity = identity;
        this.activity = activity;
    }

    @Override
    @Transactional(readOnly = true)
    public void requireActiveActor(String tenantId, String userId, boolean write) {
        if (tenantId == null || userId == null || !activity.isUserActive(userId)
                || identity.findTenant(tenantId).filter(t -> t.status() == TenantStatus.ACTIVE).isEmpty()) {
            throw denied();
        }
        var membership = identity.findTenantMembership(tenantId, userId)
                .filter(m -> m.status() == TenantMembershipStatus.ACTIVE).orElseThrow(IdentityExecutionAuthorizationService::denied);
        if (write && membership.role() == TenantRole.VIEWER) throw denied();
    }

    private static BusinessException denied() {
        return new BusinessException("Actor is no longer authorized for execution", HttpStatus.FORBIDDEN,
                "EXECUTION_ACTOR_UNAVAILABLE");
    }
}
