package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.project.api.ProjectWorkbenchApplicationApi;
import com.spaceagent.shared.api.ApiResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class PlatformProjectWorkbenchHttpController {
    private final ProjectWorkbenchApplicationApi api;
    public PlatformProjectWorkbenchHttpController(ProjectWorkbenchApplicationApi api){this.api=api;}
    @GetMapping("/sources/{sourceId}/branches")
    public ApiResponse<?> branches(@PathVariable String projectId, @PathVariable String sourceId, Authentication auth) {
        return ApiResponse.ok(api.branches(new ProjectWorkbenchApplicationApi.SourceQuery(
                PlatformHttpSupport.tenantId(auth), PlatformHttpSupport.userId(auth), projectId, sourceId)));
    }
    @GetMapping("/workspaces/{workspaceId}/environment")
    public ApiResponse<?> environment(@PathVariable String projectId,@PathVariable String workspaceId,Authentication auth){return ApiResponse.ok(api.environment(query(auth,projectId,workspaceId)));}
    @GetMapping("/workspaces/{workspaceId}/files")
    public ApiResponse<?> files(@PathVariable String projectId,@PathVariable String workspaceId,@RequestParam(defaultValue=".") String path,Authentication auth){return ApiResponse.ok(api.files(query(auth,projectId,workspaceId),path));}
    @GetMapping("/workspaces/{workspaceId}/file")
    public ApiResponse<?> file(@PathVariable String projectId,@PathVariable String workspaceId,@RequestParam String path,Authentication auth){return ApiResponse.ok(api.file(query(auth,projectId,workspaceId),path));}
    private ProjectWorkbenchApplicationApi.Query query(Authentication auth,String project,String workspace){return new ProjectWorkbenchApplicationApi.Query(PlatformHttpSupport.tenantId(auth),PlatformHttpSupport.userId(auth),project,workspace);}
}
