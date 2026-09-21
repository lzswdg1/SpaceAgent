package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.tooling.api.SkillRegistryApplicationApi;
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
@RequestMapping("/api/v1/skills")
public class PlatformSkillHttpController {
    private final SkillRegistryApplicationApi skills;

    public PlatformSkillHttpController(SkillRegistryApplicationApi skills) {
        this.skills = skills;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SkillRegistryApplicationApi.SkillView> create(
            @Valid @RequestBody SkillWriteRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(skills.create(new SkillRegistryApplicationApi.CreateSkillCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                request.name(), request.description(), request.instructions(),
                request.requiredToolIds())));
    }

    @GetMapping
    public ApiResponse<List<SkillRegistryApplicationApi.SkillView>> list(
            Authentication authentication) {
        return ApiResponse.ok(skills.list(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication)));
    }

    @GetMapping("/{skillId}")
    public ApiResponse<SkillRegistryApplicationApi.SkillView> get(
            @PathVariable String skillId,
            Authentication authentication) {
        return ApiResponse.ok(skills.get(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), skillId));
    }

    @PostMapping("/{skillId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SkillRegistryApplicationApi.SkillVersionView> createVersion(
            @PathVariable String skillId,
            @Valid @RequestBody SkillVersionWriteRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(skills.createVersion(
                new SkillRegistryApplicationApi.CreateSkillVersionCommand(
                        PlatformHttpSupport.tenantId(authentication),
                        PlatformHttpSupport.userId(authentication), skillId,
                        request.instructions(), request.requiredToolIds())));
    }

    @PostMapping("/{skillId}/versions/{versionId}/publish")
    public ApiResponse<SkillRegistryApplicationApi.SkillView> publish(
            @PathVariable String skillId,
            @PathVariable String versionId,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(skills.publish(lifecycle(authentication, skillId, versionId)));
    }

    @PostMapping("/{skillId}/versions/{versionId}/deprecate")
    public ApiResponse<SkillRegistryApplicationApi.SkillView> deprecate(
            @PathVariable String skillId,
            @PathVariable String versionId,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(skills.deprecate(lifecycle(authentication, skillId, versionId)));
    }

    @DeleteMapping("/{skillId}")
    public ApiResponse<Void> archive(
            @PathVariable String skillId,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        skills.archive(new SkillRegistryApplicationApi.ArchiveSkillCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), skillId));
        return ApiResponse.ok(null);
    }

    private SkillRegistryApplicationApi.SkillVersionLifecycleCommand lifecycle(
            Authentication authentication, String skillId, String versionId) {
        return new SkillRegistryApplicationApi.SkillVersionLifecycleCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), skillId, versionId);
    }

    public record SkillWriteRequest(
            @NotBlank @Size(max = 100) String name,
            @Size(max = 1000) String description,
            @NotBlank @Size(max = 131072) String instructions,
            @Size(max = 32) List<@NotBlank String> requiredToolIds) { }

    public record SkillVersionWriteRequest(
            @NotBlank @Size(max = 131072) String instructions,
            @Size(max = 32) List<@NotBlank String> requiredToolIds) { }
}
