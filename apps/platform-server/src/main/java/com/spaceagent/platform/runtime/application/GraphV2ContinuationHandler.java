package com.spaceagent.platform.runtime.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spaceagent.platform.runtime.api.*;
import com.spaceagent.platform.runtime.domain.RuntimeContinuationType;
import org.springframework.stereotype.Component;

@Component
public class GraphV2ContinuationHandler implements RuntimeContinuationHandler {
    private final GraphV2OrchestrationApplicationApi graph;
    private final ObjectMapper json;
    public GraphV2ContinuationHandler(GraphV2OrchestrationApplicationApi graph,ObjectMapper json){this.graph=graph;this.json=json;}
    @Override public RuntimeContinuationType type(){return RuntimeContinuationType.GRAPH_COMMAND;}
    @Override public boolean completesRun(){return false;}
    @Override public void handle(ContinuationHandlerContext context){try{Payload p=json.readValue(context.continuation().payload(),Payload.class);if(!p.agentRunId().equals(context.continuation().agentRunId()))throw new IllegalArgumentException("graph continuation Run mismatch");graph.transition(new GraphV2OrchestrationApplicationApi.TransitionCommand(p.tenantId(),p.ownerUserId(),p.agentRunId(),p.bundleHash(),p.maxDepth(),p.maxAgents(),p.remainingTokenBudget()));}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalArgumentException("graph continuation payload invalid",e);}}
    public record Payload(String tenantId,String ownerUserId,String agentRunId,String bundleHash,int maxDepth,int maxAgents,int remainingTokenBudget) {}
}
