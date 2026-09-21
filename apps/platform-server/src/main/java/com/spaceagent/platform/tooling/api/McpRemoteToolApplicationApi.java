package com.spaceagent.platform.tooling.api;import java.util.*;
public interface McpRemoteToolApplicationApi{
 List<ToolView> listTools(String tenantId,String userId,String connectionId);
 ToolResult callTool(CallCommand command);
 record CallCommand(String tenantId,String userId,String connectionId,String toolName,Map<String,Object> arguments){}
 record ToolView(String name,String description,Map<String,Object> inputSchema,
                 boolean readOnly,boolean destructive){}
 record ToolResult(boolean error,Object structuredContent,String text){}
}
