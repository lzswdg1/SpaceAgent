package com.spaceagent.platform.integration.api;

import com.spaceagent.platform.runtime.api.ProjectCodingJobApplicationApi;

public interface ProjectPlanExecutionApplicationApi {

    DispatchView dispatch(DispatchCommand command);

    record DispatchCommand(
            String tenantId,
            String ownerId,
            String projectId,
            String rootTaskId,
            String taskPlanId,
            String projectDirectoryId,
            String conversationId,
            String sourceRepositoryId,
            String agentId,
            String primaryConfigurationHash,
            String reviewerConfigurationHash,
            String baseRef,
            String reviewerAgentId) {
        public DispatchCommand(String tenantId,String ownerId,String projectId,String rootTaskId,
                String taskPlanId,String projectDirectoryId,String conversationId,
                String sourceRepositoryId,String agentId,String primaryConfigurationHash,
                String reviewerConfigurationHash,String baseRef){
            this(tenantId,ownerId,projectId,rootTaskId,taskPlanId,projectDirectoryId,
                    conversationId,sourceRepositoryId,agentId,primaryConfigurationHash,
                    reviewerConfigurationHash,baseRef,null);
        }
    }

    record DispatchView(
            String executionId,
            String taskPlanId,
            String taskPlanState,
            ProjectCodingJobApplicationApi.JobView activeJob,
            boolean materialized) {
        public DispatchView(
                String taskPlanId,
                String taskPlanState,
                ProjectCodingJobApplicationApi.JobView activeJob,
                boolean materialized) {
            this(null, taskPlanId, taskPlanState, activeJob, materialized);
        }
    }
}
