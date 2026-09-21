package com.spaceagent.platform.project.api;

public record ListSourceRepositoriesQuery(String tenantId, String userId, String projectId, int offset, int limit) {
    public ListSourceRepositoriesQuery(String tenantId,String userId,String projectId){this(tenantId,userId,projectId,0,100);}
}
