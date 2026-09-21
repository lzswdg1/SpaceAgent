package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.project.api.ProjectDirectoryApplicationApi;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/directories")
public class PlatformProjectDirectoryHttpController {
    private final ProjectDirectoryApplicationApi directories;

    public PlatformProjectDirectoryHttpController(ProjectDirectoryApplicationApi directories) {
        this.directories = directories;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProjectDirectoryApplicationApi.DirectoryView> create(
            @PathVariable String projectId,
            @Valid @RequestBody CreateRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(directories.create(new ProjectDirectoryApplicationApi.CreateCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), projectId,
                request.sourceRepositoryId(), request.name(), request.relativePath())));
    }

    @GetMapping
    public ApiResponse<List<ProjectDirectoryApplicationApi.DirectoryView>> list(
            @PathVariable String projectId, Authentication authentication) {
        return ApiResponse.ok(directories.list(new ProjectDirectoryApplicationApi.ListQuery(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), projectId)));
    }

    @GetMapping("/{directoryId}")
    public ApiResponse<ProjectDirectoryApplicationApi.DirectoryView> get(
            @PathVariable String projectId,
            @PathVariable String directoryId,
            Authentication authentication) {
        return ApiResponse.ok(directories.get(new ProjectDirectoryApplicationApi.Query(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), projectId, directoryId)));
    }

    @DeleteMapping("/{directoryId}")
    public ApiResponse<ProjectDirectoryApplicationApi.DirectoryView> archive(
            @PathVariable String projectId,
            @PathVariable String directoryId,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(directories.archive(new ProjectDirectoryApplicationApi.ArchiveCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), projectId, directoryId)));
    }

    public record CreateRequest(
            @NotBlank @Size(max = 36) String sourceRepositoryId,
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) String relativePath) {
    }
}
