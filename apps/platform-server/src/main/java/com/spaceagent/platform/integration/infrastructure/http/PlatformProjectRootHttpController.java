package com.spaceagent.platform.integration.infrastructure.http;
import com.spaceagent.platform.project.api.ProjectRootApplicationApi;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/project-roots")
public class PlatformProjectRootHttpController {
    private final ProjectRootApplicationApi roots;
    public PlatformProjectRootHttpController(ProjectRootApplicationApi roots){this.roots=roots;}
    @GetMapping public ApiResponse<?> list(Authentication auth){return ApiResponse.ok(roots.list(PlatformHttpSupport.tenantId(auth),PlatformHttpSupport.userId(auth)));}
    @PostMapping public ApiResponse<?> create(@Valid @RequestBody CreateRequest body,Authentication auth){PlatformHttpSupport.requireWrite(auth);return ApiResponse.ok(roots.create(new ProjectRootApplicationApi.CreateCommand(PlatformHttpSupport.tenantId(auth),PlatformHttpSupport.userId(auth),body.projectId(),body.sourceRepositoryId(),body.name())));}
    @PostMapping("/{rootId}/prepare") public ApiResponse<?> prepare(@PathVariable String rootId,Authentication auth){PlatformHttpSupport.requireWrite(auth);return ApiResponse.ok(roots.prepare(PlatformHttpSupport.tenantId(auth),PlatformHttpSupport.userId(auth),rootId));}
    @DeleteMapping("/{rootId}") public ApiResponse<?> delete(@PathVariable String rootId,Authentication auth){PlatformHttpSupport.requireWrite(auth);return ApiResponse.ok(roots.archive(PlatformHttpSupport.tenantId(auth),PlatformHttpSupport.userId(auth),rootId));}
    @PatchMapping("/{rootId}") public ApiResponse<?> rename(@PathVariable String rootId,@Valid @RequestBody RenameRequest body,Authentication auth){PlatformHttpSupport.requireWrite(auth);return ApiResponse.ok(roots.rename(PlatformHttpSupport.tenantId(auth),PlatformHttpSupport.userId(auth),rootId,body.name()));}
    public record RenameRequest(@NotBlank @Size(max=120) String name){}
    public record CreateRequest(@Size(max=36) String projectId,@Size(max=36) String sourceRepositoryId,@NotBlank @Size(max=120) String name){}
}
