package com.spaceagent.platform.tooling.domain;import java.util.*;
public interface McpRemoteToolGateway{
 List<RemoteTool> listTools(McpConnection connection,Map<String,String> auth);
 RemoteResult callTool(McpConnection connection,Map<String,String> auth,String tool,Map<String,Object> arguments);
 default List<RemoteResource> listResources(McpConnection connection,Map<String,String> auth){throw new UnsupportedOperationException("MCP Resources unsupported");}
 default RemoteResourceContent readResource(McpConnection connection,Map<String,String> auth,String uri){throw new UnsupportedOperationException("MCP Resources unsupported");}
 default List<RemotePrompt> listPrompts(McpConnection connection,Map<String,String> auth){throw new UnsupportedOperationException("MCP Prompts unsupported");}
 default RemotePromptResult getPrompt(McpConnection connection,Map<String,String> auth,String name,Map<String,String> arguments){throw new UnsupportedOperationException("MCP Prompts unsupported");}
 record RemoteTool(String name,String description,Map<String,Object> inputSchema,
                   boolean readOnly,boolean destructive){}
 record RemoteResult(boolean error,Object structuredContent,String text){}
 record RemoteResource(String uri,String name,String title,String description,String mimeType,Long size){}
 record RemoteResourceContent(String uri,String mimeType,String text,boolean blob){}
 record RemotePrompt(String name,String title,String description,List<RemotePromptArgument> arguments){}
 record RemotePromptArgument(String name,String title,String description,boolean required){}
 record RemotePromptMessage(String role,String text,boolean textContent){}
 record RemotePromptResult(String description,List<RemotePromptMessage> messages){}
}
