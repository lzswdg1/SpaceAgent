package com.spaceagent.platform.project.api;
import com.spaceagent.platform.project.domain.WorkspaceCodingGateway;import java.util.List;
public interface CodingWorkspaceApplicationApi{
 Result execute(Command command);Snapshot snapshot(Query query);
 record Command(String tenantId,String userId,String projectId,String taskId,String workspaceId,WorkspaceCodingGateway.Type type,String relativePath,String content,String executable,List<String> arguments,int timeoutSeconds,String agentRunId,String toolCallId){public Command(String tenantId,String userId,String projectId,String taskId,String workspaceId,WorkspaceCodingGateway.Type type,String relativePath,String content,String executable,List<String> arguments,int timeoutSeconds){this(tenantId,userId,projectId,taskId,workspaceId,type,relativePath,content,executable,arguments,timeoutSeconds,null,null);}}
 record Query(String tenantId,String userId,String projectId,String taskId,String workspaceId,String agentRunId,String toolCallId){public Query(String tenantId,String userId,String projectId,String taskId,String workspaceId){this(tenantId,userId,projectId,taskId,workspaceId,null,null);}}
 record Result(int exitCode,String stdout,String stderr,List<String> changedFiles){}
 record Snapshot(String headCommit,String patch,String status,List<String> changedFiles,boolean truncated){public Snapshot(String headCommit,String patch,String status,List<String> changedFiles){this(headCommit,patch,status,changedFiles,false);}}
}
