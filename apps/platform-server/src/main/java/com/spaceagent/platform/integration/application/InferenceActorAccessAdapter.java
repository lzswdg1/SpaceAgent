package com.spaceagent.platform.integration.application;
import com.spaceagent.platform.inference.domain.InferenceActorAccessPort;
import com.spaceagent.platform.identity.api.IdentityActivityApplicationApi;
import com.spaceagent.platform.identity.api.IdentityApplicationApi;
import org.springframework.stereotype.Component;

@Component public class InferenceActorAccessAdapter implements InferenceActorAccessPort {
    private final IdentityActivityApplicationApi activity;private final IdentityApplicationApi identity;
    public InferenceActorAccessAdapter(IdentityActivityApplicationApi activity,IdentityApplicationApi identity){this.activity=activity;this.identity=identity;}
    public boolean active(String tenant,String actor){return activity.isUserActive(actor)
        && identity.findTenant(tenant).filter(t->"ACTIVE".equals(t.status().name())).isPresent()
        && identity.findTenantMembership(tenant,actor).filter(m->"ACTIVE".equals(m.status().name())).isPresent();}
}
