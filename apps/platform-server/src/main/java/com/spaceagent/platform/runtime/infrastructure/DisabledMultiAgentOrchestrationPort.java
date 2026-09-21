package com.spaceagent.platform.runtime.infrastructure;

import com.spaceagent.platform.runtime.domain.MultiAgentOrchestrationPort;
import com.spaceagent.platform.runtime.domain.GraphV2OrchestrationPort;
import com.spaceagent.platform.runtime.domain.multiagent.GraphV2Contract;
import com.spaceagent.platform.runtime.domain.MultiAgentOrchestrationUnavailableException;
import com.spaceagent.platform.runtime.domain.multiagent.MultiAgentOrchestrationRequest;
import com.spaceagent.platform.runtime.domain.multiagent.MultiAgentOrchestrationResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Default fail-closed adapter; M18 does not change production routing. */
@Component
@ConditionalOnProperty(
        prefix = "platform.multi-agent-orchestrator",
        name = "mode",
        havingValue = "none",
        matchIfMissing = true)
public class DisabledMultiAgentOrchestrationPort implements MultiAgentOrchestrationPort, GraphV2OrchestrationPort {

    @Override
    public MultiAgentOrchestrationResponse orchestrate(
            MultiAgentOrchestrationRequest request) {
        throw new MultiAgentOrchestrationUnavailableException(
                "TypeScript Multi-Agent orchestrator is not enabled");
    }

    @Override
    public GraphV2Contract.Result transition(GraphV2Contract.Request request) {
        throw new MultiAgentOrchestrationUnavailableException(
                "TypeScript graph/v2 orchestrator is not enabled");
    }
}
