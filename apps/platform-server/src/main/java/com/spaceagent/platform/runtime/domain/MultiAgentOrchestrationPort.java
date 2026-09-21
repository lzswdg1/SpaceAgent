package com.spaceagent.platform.runtime.domain;

import com.spaceagent.platform.runtime.domain.multiagent.MultiAgentOrchestrationRequest;
import com.spaceagent.platform.runtime.domain.multiagent.MultiAgentOrchestrationResponse;

/** Optional TypeScript compute boundary; Java remains the durable command authority. */
public interface MultiAgentOrchestrationPort {

    MultiAgentOrchestrationResponse orchestrate(MultiAgentOrchestrationRequest request);
}
