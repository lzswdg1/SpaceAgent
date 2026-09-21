package com.spaceagent.platform.runtime.api;

import com.spaceagent.platform.runtime.domain.multiagent.MultiAgentOrchestrationResponse;

public interface MultiAgentOrchestrationApplicationApi {

    MultiAgentOrchestrationResponse invoke(InvokeMultiAgentOrchestrationCommand command);
}
