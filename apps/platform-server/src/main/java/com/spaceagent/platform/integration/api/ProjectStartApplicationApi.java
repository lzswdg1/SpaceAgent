package com.spaceagent.platform.integration.api;
import com.spaceagent.platform.project.api.*;
import com.spaceagent.platform.runtime.api.ProjectPlanExecutionApplicationApi;
public interface ProjectStartApplicationApi {
    CodingResult coding(String tenant,String user,String key,CodingInput input);
    ProjectRootApplicationApi.RootView githubRoot(String tenant,String user,String key,RootInput input);
    record CodingInput(String projectId,String directoryId,String sourceId,String conversationId,String agentId,String reviewerAgentId,String baseRef,String goal) { }
    record RootInput(String name,String connectionId,String githubUrl) { }
    record CodingResult(TaskView root,TaskView child,TaskPlanView plan,ProjectPlanExecutionApplicationApi.ExecutionView execution) { }
}
