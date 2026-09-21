package com.spaceagent.platform.integration.application;

import com.spaceagent.platform.identity.api.IdentityExecutionAuthorizationApi;
import com.spaceagent.platform.inference.domain.InferenceExecutionAdmissionPort;
import com.spaceagent.platform.runtime.api.RuntimeApplicationApi;
import com.spaceagent.shared.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class IdentityInferenceExecutionAdmission implements InferenceExecutionAdmissionPort {
    private final RuntimeApplicationApi runtime;
    private final IdentityExecutionAuthorizationApi identity;

    public IdentityInferenceExecutionAdmission(RuntimeApplicationApi runtime, IdentityExecutionAuthorizationApi identity) {
        this.runtime = runtime;
        this.identity = identity;
    }

    @Override
    public void requireDispatch(String tenantId, String agentRunId) {
        var run = runtime.findRun(agentRunId).filter(r -> tenantId != null && tenantId.equals(r.tenantId()))
                .orElseThrow(() -> new BusinessException("Run execution scope is unavailable", HttpStatus.FORBIDDEN,
                        "EXECUTION_ACTOR_UNAVAILABLE"));
        identity.requireActiveActor(run.tenantId(), run.ownerId(), true);
    }
}
