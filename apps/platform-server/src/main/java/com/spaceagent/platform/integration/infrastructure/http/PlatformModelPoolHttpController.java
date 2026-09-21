package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.inference.api.AddModelPoolMemberCommand;
import com.spaceagent.platform.inference.api.CreateModelPoolCommand;
import com.spaceagent.platform.inference.api.ModelPoolApplicationApi;
import com.spaceagent.platform.inference.api.ModelPoolMemberView;
import com.spaceagent.platform.inference.api.ModelPoolResolutionView;
import com.spaceagent.platform.inference.api.ModelPoolView;
import com.spaceagent.platform.inference.api.RemoveModelPoolMemberCommand;
import com.spaceagent.platform.inference.api.UpdateModelPoolStatusCommand;
import com.spaceagent.platform.inference.domain.ModelPoolRoutingStrategy;
import com.spaceagent.platform.inference.domain.ModelPoolVisibility;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
@RequestMapping("/api/v1/model-pools")
public class PlatformModelPoolHttpController {

    private final ModelPoolApplicationApi modelPoolApi;

    public PlatformModelPoolHttpController(ModelPoolApplicationApi modelPoolApi) {
        this.modelPoolApi = modelPoolApi;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ModelPoolView> createPool(
            @Valid @RequestBody CreatePoolRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(modelPoolApi.createPool(new CreateModelPoolCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication),
                request.name(), request.visibility(), request.routingStrategy(),
                request.fallbackEnabled())));
    }

    @GetMapping
    public ApiResponse<List<ModelPoolView>> listPools(Authentication authentication) {
        return ApiResponse.ok(modelPoolApi.listPools(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication)));
    }

    @GetMapping("/{poolId}")
    public ApiResponse<ModelPoolView> getPool(
            @PathVariable String poolId,
            Authentication authentication) {
        return ApiResponse.ok(modelPoolApi.getPool(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), poolId));
    }

    @GetMapping("/{poolId}/members")
    public ApiResponse<List<ModelPoolMemberView>> listMembers(
            @PathVariable String poolId,
            Authentication authentication) {
        return ApiResponse.ok(modelPoolApi.listMembers(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), poolId));
    }

    @PostMapping("/{poolId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ModelPoolMemberView> addMember(
            @PathVariable String poolId,
            @Valid @RequestBody AddPoolMemberRequest request,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(modelPoolApi.addMember(new AddModelPoolMemberCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), poolId,
                request.providerId(), request.providerModelId(),
                request.priority(), request.weight())));
    }

    @DeleteMapping("/{poolId}/members/{memberId}")
    public ApiResponse<Void> removeMember(
            @PathVariable String poolId,
            @PathVariable String memberId,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        modelPoolApi.removeMember(new RemoveModelPoolMemberCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), poolId, memberId));
        return ApiResponse.ok(null);
    }

    @PostMapping("/{poolId}/activate")
    public ApiResponse<ModelPoolView> activatePool(
            @PathVariable String poolId,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(modelPoolApi.activatePool(statusCommand(poolId, authentication)));
    }

    @PostMapping("/{poolId}/disable")
    public ApiResponse<ModelPoolView> disablePool(
            @PathVariable String poolId,
            Authentication authentication) {
        PlatformHttpSupport.requireWrite(authentication);
        return ApiResponse.ok(modelPoolApi.disablePool(statusCommand(poolId, authentication)));
    }

    @GetMapping("/{poolId}/resolution")
    public ApiResponse<ModelPoolResolutionView> resolvePool(
            @PathVariable String poolId,
            Authentication authentication) {
        return ApiResponse.ok(modelPoolApi.resolvePool(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), poolId));
    }

    private static UpdateModelPoolStatusCommand statusCommand(
            String poolId,
            Authentication authentication) {
        return new UpdateModelPoolStatusCommand(
                PlatformHttpSupport.tenantId(authentication),
                PlatformHttpSupport.userId(authentication), poolId);
    }

    public record CreatePoolRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull ModelPoolVisibility visibility,
            ModelPoolRoutingStrategy routingStrategy,
            boolean fallbackEnabled) {
    }

    public record AddPoolMemberRequest(
            @NotBlank String providerId,
            @NotBlank String providerModelId,
            @Min(0) @Max(10000) Integer priority,
            @Min(1) @Max(1000) Integer weight) {
    }
}
