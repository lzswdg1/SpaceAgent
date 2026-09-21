package com.spaceagent.platform.automation.api;

import java.time.Instant;

public interface AutomationEventExecutionApplicationApi {
    DispatchView dispatch(DispatchCommand command);
    DispatchView resumeApproval(ResumeApprovalCommand command);

    void execute(ExecuteCommand command);

    record DispatchCommand(String occurrenceId, String approvalId,Long expectedOccurrenceRevision) {
        public DispatchCommand(String occurrenceId,String approvalId){this(occurrenceId,approvalId,null);}
    }
    record ResumeApprovalCommand(
            String tenantId, String ownerId, String occurrenceId,
            String approvalId, long expectedOccurrenceRevision) {}

    record ExecuteCommand(
            String occurrenceId,
            String deliveryId,
            String dispatchRunId,
            String conversationId,
            String configurationHash,
            String leaseToken,
            long fencingToken,
            Instant leaseUntil) {
    }

    record DispatchView(
            String occurrenceId,
            String deliveryId,
            String status,
            String dispatchRunId,
            String continuationId,
            Instant retryAt,
            String safeCode) {
    }
}
