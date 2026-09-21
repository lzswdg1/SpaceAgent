package com.spaceagent.platform.runtime.api;

import com.spaceagent.platform.runtime.domain.GraphSessionState;
import com.spaceagent.platform.runtime.domain.multiagent.GraphV2Contract;
import java.util.List;

public interface GraphV2RuntimeApplicationApi {
    SessionView start(StartCommand command);
    Result accept(AcceptCommand command);
    ExecutionView beginExecution(BeginExecutionCommand command);
    SessionView completeExecution(CompleteExecutionCommand command);
    SessionView blockExecutionUnknown(BlockExecutionCommand command);

    record StartCommand(String tenantId,String ownerUserId,String agentRunId,String bundleHash) {}
    record AcceptCommand(String tenantId,String ownerUserId,String graphSessionId,
                         GraphV2Contract.Result result,int maxDepth,int maxAgents,
                         int remainingTokenBudget) {
        public AcceptCommand(String tenantId,String ownerUserId,String graphSessionId,
                             GraphV2Contract.Result result) {
            this(tenantId,ownerUserId,graphSessionId,result,2,2,0);
        }
    }
    record BeginExecutionCommand(
            String tenantId, String ownerUserId, String graphSessionId, String commandId,
            String inputHash, String executionOwner, String executionToken,
            long executionFencingToken) {}
    record CompleteExecutionCommand(
            String tenantId, String ownerUserId, String graphSessionId,
            String commandId, String inputHash, String executionOwner,
            String executionToken, long executionFencingToken, int maxDepth,
            int maxAgents, int remainingTokenBudget) {}
    record BlockExecutionCommand(
            String tenantId, String ownerUserId, String graphSessionId,
            String commandId, String inputHash, String executionOwner,
            String executionToken, long executionFencingToken) {}
    record SessionView(String graphSessionId,String agentRunId,String bundleHash,long cursorSequence,
                       List<String> completedCommandIds,String pendingCommandId,GraphSessionState state,long revision) {}
    record Result(String graphSessionId,GraphSessionState state,long revision,String commandId,boolean replayed) {}
    record ExecutionView(
            String graphSessionId, String agentRunId, String tenantId, String ownerUserId,
            String commandId, String inputHash, GraphV2Contract.Kind kind,
            long nextCursorSequence, String payloadJson, boolean execute) {}
}
