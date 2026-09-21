package com.spaceagent.platform.runtime.api;
import com.spaceagent.platform.runtime.domain.multiagent.GraphV2Contract;
public interface GraphV2OrchestrationApplicationApi { TransitionView transition(TransitionCommand command);record TransitionCommand(String tenantId,String ownerUserId,String agentRunId,String bundleHash,int maxDepth,int maxAgents,int remainingTokenBudget){}record TransitionView(GraphV2RuntimeApplicationApi.SessionView session,GraphV2Contract.Result proposal,GraphV2RuntimeApplicationApi.Result acceptance){} }
