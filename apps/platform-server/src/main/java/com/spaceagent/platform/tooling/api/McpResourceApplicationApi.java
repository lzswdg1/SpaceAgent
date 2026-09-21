package com.spaceagent.platform.tooling.api;

import java.util.List;

public interface McpResourceApplicationApi {
    List<ResourceView> list(ListCommand command);
    ResourceContent read(ReadCommand command);
    record Binding(String installationId,String connectionId,String serverVersionId,String capabilitySnapshotId,
            long connectionRevision,String snapshotSha256,List<String> allowedToolNames){}
    record ListCommand(String tenantId,String ownerUserId,Binding binding){}
    record ReadCommand(String tenantId,String ownerUserId,Binding binding,String uri){}
    record ResourceView(String uri,String name,String title,String description,String mimeType,Long size){}
    record ResourceContent(String uri,String mimeType,String text,int utf8Bytes){}
}
