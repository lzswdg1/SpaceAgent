package com.spaceagent.platform.artifact.api;

public interface ArtifactObjectMaintenanceApplicationApi {
    DeletionView scheduleDeletion(ScheduleDeletionCommand command);
    boolean runDeletionOnce(String workerId,int leaseSeconds);
    int sweepExpiredStaging(int limit);
    boolean recoverExpiredDeletion();
    DeletionView retryBlockedDeletion(RetryBlockedDeletionCommand command);
    record ScheduleDeletionCommand(String tenantId,String objectId){}
    record RetryBlockedDeletionCommand(String tenantId,String objectId){}
    record DeletionView(String id,String objectId,String state,long fencingToken,long revision,String safeErrorCode){}
}
