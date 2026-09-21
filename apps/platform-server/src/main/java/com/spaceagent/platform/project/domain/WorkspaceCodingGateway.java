package com.spaceagent.platform.project.domain;
import java.util.List;
public interface WorkspaceCodingGateway {
    CodingResult execute(Workspace workspace, CodingOperation operation);
    WorkspaceSnapshot snapshot(Workspace workspace);
    enum Type { WRITE_FILE, DELETE_FILE, RUN_COMMAND }
    record CodingOperation(Type type,String relativePath,String content,String executable,List<String> arguments,int timeoutSeconds){}
    record CodingResult(int exitCode,String stdout,String stderr,List<String> changedFiles){}
    record WorkspaceSnapshot(String headCommit,String patch,String status,List<String> changedFiles){}
}
