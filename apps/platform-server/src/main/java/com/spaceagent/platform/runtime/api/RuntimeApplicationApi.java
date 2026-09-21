package com.spaceagent.platform.runtime.api;

import java.util.List;
import java.util.Optional;

/**
 * Public runtime application API. Runtime owns durable execution flow and does not
 * expose its persistence implementation to other modules.
 */
public interface RuntimeApplicationApi {

    AgentRunView startRun(StartAgentRunCommand command);

    Optional<AgentRunView> findRun(String agentRunId);
    default Optional<AgentRunView> findLatestChatRun(String tenantId,String ownerId,String conversationId){return Optional.empty();}

    AgentRunView markRunInProgress(String agentRunId);

    AgentRunView resumeFenced(ResumeAgentRunCommand command);

    AgentRunView completeFenced(CompleteAgentRunFencedCommand command);

    AgentRunView failFenced(FailAgentRunFencedCommand command);

    AgentRunView cancelFenced(CancelAgentRunFencedCommand command);

    AgentRunView markRunWaitingForTool(String agentRunId);

    AgentRunView markRunWaitingForUser(String agentRunId);

    AgentRunView markRunHandedOff(String agentRunId, String handoffId);

    RunStepView startStep(StartRunStepCommand command);

    RunStepView completeStep(CompleteRunStepCommand command);

    RunStepView failStep(FailRunStepCommand command);

    List<RunStepView> findSteps(String agentRunId);

    CheckpointView createCheckpoint(CreateCheckpointCommand command);

    Optional<CheckpointView> findLatestCheckpoint(String agentRunId);

    Optional<CheckpointView> findLatestCheckpointByPhase(String agentRunId, String phase);

    RecoveryView requestRecovery(RequestRecoveryCommand command);

    java.util.Optional<RecoveryResumeStateView> reconstructResumeState(String agentRunId);

    RecoveryView acceptRecovery(AcceptRecoveryCommand command);

    HandoffView createHandoff(CreateHandoffCommand command);

    java.util.Optional<HandoffView> findHandoff(String handoffId);

    HandoffView acceptHandoff(AcceptHandoffCommand command);

    HandoffView completeHandoff(CompleteHandoffCommand command);

    List<HandoffView> findHandoffsBySourceRun(String sourceAgentRunId);

    AgentRunView advanceCursor(AdvanceExecutionCursorCommand command);

    RunEventView recordEvent(RecordRunEventCommand command);

    List<RunEventView> findEvents(String agentRunId);

    RunEventPageView findEventsAfter(String agentRunId, long afterSequence, int limit);

    long latestEventSequence(String agentRunId);

    AgentRunView cancel(CancelAgentRunCommand command);

    AgentRunView complete(CompleteAgentRunCommand command);

    AgentRunView fail(FailAgentRunCommand command);
}
