package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.integration.api.GithubMcpProjectImportApplicationApi;
import com.spaceagent.platform.project.api.*;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Path-free HTTP adapter for Project source metadata. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/sources")
public class PlatformSourceRepositoryHttpController {
    private final SourceRepositoryApplicationApi api;
    private final GithubMcpProjectImportApplicationApi githubMcp;
    public PlatformSourceRepositoryHttpController(
            SourceRepositoryApplicationApi api,
            GithubMcpProjectImportApplicationApi githubMcp) {
        this.api = api;
        this.githubMcp = githubMcp;
    }

    @PostMapping("/github-mcp")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SourceRepositoryView> importGithubMcp(
            @PathVariable String projectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ImportGithubMcpRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(githubMcp.importRepository(
                new GithubMcpProjectImportApplicationApi.Command(
                        PlatformHttpSupport.tenantId(authentication),
                        PlatformHttpSupport.userId(authentication), projectId,
                        request.connectionId(), request.providerRepositoryId(),
                        request.githubUrl(), idempotencyKey)));
    }

    @PostMapping("/local")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SourceRepositoryView> importLocal(
            @PathVariable String projectId, @Valid @RequestBody ImportLocalRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(api.importLocal(new ImportLocalRepositoryCommand(
                PlatformHttpSupport.tenantId(authentication), PlatformHttpSupport.userId(authentication),
                projectId, request.bridgeId(), request.rootHandle(), request.displayName(),
                request.defaultBranch())));
    }

    @GetMapping
    public ApiResponse<List<SourceRepositoryView>> list(
            @PathVariable String projectId, Authentication authentication,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="0") int offset,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue="100") int limit) {
        return ApiResponse.ok(api.list(new ListSourceRepositoriesQuery(
                PlatformHttpSupport.tenantId(authentication), PlatformHttpSupport.userId(authentication),
                projectId,offset,limit)));
    }

    @GetMapping("/{sourceId}")
    public ApiResponse<SourceRepositoryView> get(
            @PathVariable String projectId, @PathVariable String sourceId,
            Authentication authentication) {
        return ApiResponse.ok(api.get(new GetSourceRepositoryQuery(
                PlatformHttpSupport.tenantId(authentication), PlatformHttpSupport.userId(authentication),
                projectId, sourceId)));
    }

    @DeleteMapping("/{sourceId}")
    public ApiResponse<SourceRepositoryView> archive(
            @PathVariable String projectId, @PathVariable String sourceId,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(api.archive(new ArchiveSourceRepositoryCommand(
                PlatformHttpSupport.tenantId(authentication), PlatformHttpSupport.userId(authentication),
                projectId, sourceId)));
    }

    public record ImportGithubMcpRequest(
            @NotBlank String connectionId,
            @Size(max = 180) String providerRepositoryId,
            @Size(max = 1000) String githubUrl) { }

    /** Deliberately contains no path/localPath/rootPath field. */
    public record ImportLocalRequest(
            @NotBlank String bridgeId,
            @NotBlank @Size(min = 8, max = 128) String rootHandle,
            @NotBlank @Size(max = 200) String displayName,
            @Size(max = 255) String defaultBranch) { }
}
