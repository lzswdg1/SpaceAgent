package com.spaceagent.platform.integration.infrastructure.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.spaceagent.platform.project.api.AddProjectMemberCommand;
import com.spaceagent.platform.project.api.ArchiveProjectCommand;
import com.spaceagent.platform.project.api.CreateProjectCommand;
import com.spaceagent.platform.project.api.GetProjectQuery;
import com.spaceagent.platform.project.api.ListProjectMembersQuery;
import com.spaceagent.platform.project.api.ListProjectsQuery;
import com.spaceagent.platform.project.api.ProjectApplicationApi;
import com.spaceagent.platform.project.api.ProjectMembershipView;
import com.spaceagent.platform.project.api.ProjectView;
import com.spaceagent.platform.project.api.RemoveProjectMemberCommand;
import com.spaceagent.platform.project.api.UpdateProjectCommand;
import com.spaceagent.platform.project.domain.ProjectRole;
import com.spaceagent.shared.api.ApiResponse;
import com.spaceagent.shared.exception.BusinessException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** HTTP adapter for the tenant-scoped Project Application API. */
@RestController
@RequestMapping("/api/v1/projects")
public class PlatformProjectHttpController {

    private final ProjectApplicationApi projectApi;

    public PlatformProjectHttpController(ProjectApplicationApi projectApi) {
        this.projectApi = projectApi;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProjectView> createProject(
            @Valid @RequestBody CreateProjectRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(projectApi.createProject(new CreateProjectCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                request.name(),
                request.description())));
    }

    @GetMapping
    public ApiResponse<List<ProjectView>> listProjects(Authentication authentication) {
        return ApiResponse.ok(projectApi.listProjects(new ListProjectsQuery(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication))));
    }

    @GetMapping("/{projectId}")
    public ApiResponse<ProjectView> getProject(
            @PathVariable String projectId,
            Authentication authentication) {
        return ApiResponse.ok(projectApi.getProject(new GetProjectQuery(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                projectId)));
    }

    @PatchMapping("/{projectId}")
    public ApiResponse<ProjectView> updateProject(
            @PathVariable String projectId,
            @RequestBody JsonNode request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        if (request == null || !request.isObject()) {
            throw invalidPatch("Project patch must be a JSON object");
        }
        boolean namePresent = request.has("name");
        boolean descriptionPresent = request.has("description");
        if (!namePresent && !descriptionPresent) {
            throw invalidPatch("Project patch must contain name or description");
        }
        if (namePresent && (!request.get("name").isTextual()
                || request.get("name").asText().isBlank())) {
            throw invalidPatch("Project name must be non-blank text");
        }
        if (descriptionPresent && !request.get("description").isNull()
                && !request.get("description").isTextual()) {
            throw invalidPatch("Project description must be text or null");
        }

        String name = namePresent ? request.get("name").asText() : null;
        String description = descriptionPresent && !request.get("description").isNull()
                ? request.get("description").asText()
                : null;
        return ApiResponse.ok(projectApi.updateProject(new UpdateProjectCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                projectId,
                name,
                description,
                descriptionPresent)));
    }

    @DeleteMapping("/{projectId}")
    public ApiResponse<ProjectView> archiveProject(
            @PathVariable String projectId,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(projectApi.archiveProject(new ArchiveProjectCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                projectId)));
    }

    @PostMapping("/{projectId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProjectMembershipView> addMember(
            @PathVariable String projectId,
            @Valid @RequestBody AddProjectMemberRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(projectApi.addMember(new AddProjectMemberCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                projectId,
                request.userId(),
                request.role())));
    }

    @GetMapping("/{projectId}/members")
    public ApiResponse<List<ProjectMembershipView>> listMembers(
            @PathVariable String projectId,
            Authentication authentication) {
        return ApiResponse.ok(projectApi.listMembers(new ListProjectMembersQuery(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                projectId)));
    }

    @DeleteMapping("/{projectId}/members/{memberUserId}")
    public ApiResponse<Void> removeMember(
            @PathVariable String projectId,
            @PathVariable String memberUserId,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        projectApi.removeMember(new RemoveProjectMemberCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                projectId,
                memberUserId));
        return ApiResponse.ok(null);
    }

    private static BusinessException invalidPatch(String message) {
        return new BusinessException(message, HttpStatus.BAD_REQUEST, "PROJECT_PATCH_INVALID");
    }

    public record CreateProjectRequest(
            @NotBlank @Size(max = 128) String name,
            String description) {
    }

    public record AddProjectMemberRequest(
            @NotBlank String userId,
            @NotNull ProjectRole role) {
    }
}
