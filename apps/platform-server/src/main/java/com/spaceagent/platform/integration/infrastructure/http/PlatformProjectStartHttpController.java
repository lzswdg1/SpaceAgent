package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.integration.api.ProjectStartApplicationApi;
import com.spaceagent.platform.project.api.ProjectRootApplicationApi;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class PlatformProjectStartHttpController {
    private final ProjectStartApplicationApi starts;
    public PlatformProjectStartHttpController(ProjectStartApplicationApi starts){this.starts=starts;}
    @PostMapping("/api/v1/projects/{projectId}/coding-starts")
    public ApiResponse<ProjectStartApplicationApi.CodingResult> coding(@PathVariable String projectId,
            @RequestHeader(value="Idempotency-Key",required=false)String key,@Valid @RequestBody CodingRequest body,Authentication auth){
        PlatformHttpSupport.requireWrite(auth);
        return ApiResponse.ok(starts.coding(PlatformHttpSupport.tenantId(auth),PlatformHttpSupport.userId(auth),key,
                new ProjectStartApplicationApi.CodingInput(projectId,body.directoryId(),body.sourceId(),body.conversationId(),body.agentId(),body.reviewerAgentId(),body.baseRef(),body.goal())));
    }
    @PostMapping("/api/v1/project-roots/github")
    public ApiResponse<ProjectRootApplicationApi.RootView> root(@RequestHeader(value="Idempotency-Key",required=false)String key,
            @Valid @RequestBody RootRequest body,Authentication auth){
        PlatformHttpSupport.requireWrite(auth);
        return ApiResponse.ok(starts.githubRoot(PlatformHttpSupport.tenantId(auth),PlatformHttpSupport.userId(auth),key,
                new ProjectStartApplicationApi.RootInput(body.name(),body.connectionId(),body.githubUrl())));
    }
    public record CodingRequest(@NotBlank @Size(max=36)String directoryId,@NotBlank @Size(max=36)String sourceId,
            @NotBlank @Size(max=36)String conversationId,@NotBlank @Size(max=64)String agentId,
            @NotBlank @Size(max=64)String reviewerAgentId,@Size(max=240)String baseRef,@NotBlank @Size(max=4000)String goal) { }
    public record RootRequest(@NotBlank @Size(max=120)String name,@NotBlank @Size(max=36)String connectionId,
            @NotBlank @Size(max=2048)String githubUrl) { }
}
