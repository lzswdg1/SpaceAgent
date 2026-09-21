package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.identity.api.AddOrganizationMemberCommand;
import com.spaceagent.platform.identity.api.CreateOrganizationCommand;
import com.spaceagent.platform.identity.api.LeaveOrganizationCommand;
import com.spaceagent.platform.identity.api.OrganizationApplicationApi;
import com.spaceagent.platform.identity.api.OrganizationLeaveView;
import com.spaceagent.platform.identity.api.OrganizationMembershipView;
import com.spaceagent.platform.identity.api.OrganizationSummaryView;
import com.spaceagent.platform.identity.api.OrganizationView;
import com.spaceagent.platform.identity.api.RemoveOrganizationMemberCommand;
import com.spaceagent.platform.identity.api.TransferOrganizationOwnershipCommand;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.platform.integration.infrastructure.PlatformTokenIssuer;
import com.spaceagent.shared.api.ApiResponse;
import com.spaceagent.shared.auth.AuthToken;
import com.spaceagent.shared.exception.BusinessException;
import jakarta.validation.Valid;
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

/** Product-facing Organization routes backed by the existing Tenant boundary. */
@RestController
@RequestMapping("/api/v1/organizations")
public class PlatformOrganizationHttpController {

    private final OrganizationApplicationApi organizationApi;
    private final PlatformTokenIssuer tokenIssuer;

    public PlatformOrganizationHttpController(
            OrganizationApplicationApi organizationApi,
            PlatformTokenIssuer tokenIssuer) {
        this.organizationApi = organizationApi;
        this.tokenIssuer = tokenIssuer;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrganizationView> createOrganization(
            @Valid @RequestBody CreateOrganizationRequest request,
            Authentication authentication) {
        return ApiResponse.ok(organizationApi.createOrganization(new CreateOrganizationCommand(
                PlatformHttpSupport.userId(authentication), request.name(), request.slug())));
    }

    @GetMapping
    public ApiResponse<List<OrganizationSummaryView>> listOrganizations(
            Authentication authentication) {
        return ApiResponse.ok(organizationApi.listOrganizations(
                PlatformHttpSupport.userId(authentication)));
    }

    @GetMapping("/{organizationId}")
    public ApiResponse<OrganizationView> getOrganization(
            @PathVariable String organizationId,
            Authentication authentication) {
        return ApiResponse.ok(organizationApi.getOrganization(
                organizationId, PlatformHttpSupport.userId(authentication)));
    }

    @PostMapping("/{organizationId}/switch")
    public ApiResponse<AuthToken> switchOrganization(
            @PathVariable String organizationId,
            Authentication authentication) {
        return ApiResponse.ok(tokenIssuer.switchOrganization(
                PlatformHttpSupport.userId(authentication), organizationId));
    }

    @GetMapping("/{organizationId}/members")
    public ApiResponse<List<OrganizationMembershipView>> listMembers(
            @PathVariable String organizationId,
            Authentication authentication) {
        return ApiResponse.ok(organizationApi.listMembers(
                organizationId, PlatformHttpSupport.userId(authentication)));
    }

    @PostMapping("/{organizationId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrganizationMembershipView> addMember(
            @PathVariable String organizationId,
            @Valid @RequestBody AddMemberRequest request,
            Authentication authentication) {
        requireActiveContext(organizationId, authentication);
        return ApiResponse.ok(organizationApi.addMember(new AddOrganizationMemberCommand(
                PlatformHttpSupport.userId(authentication), organizationId,
                request.userId(), request.role())));
    }

    @DeleteMapping("/{organizationId}/members/{memberUserId}")
    public ApiResponse<Void> removeMember(
            @PathVariable String organizationId,
            @PathVariable String memberUserId,
            Authentication authentication) {
        requireActiveContext(organizationId, authentication);
        organizationApi.removeMember(new RemoveOrganizationMemberCommand(
                PlatformHttpSupport.userId(authentication), organizationId, memberUserId));
        return ApiResponse.ok(null);
    }

    @PostMapping("/{organizationId}/transfer-owner")
    public ApiResponse<OrganizationView> transferOwnership(
            @PathVariable String organizationId,
            @Valid @RequestBody TransferOwnerRequest request,
            Authentication authentication) {
        requireActiveContext(organizationId, authentication);
        return ApiResponse.ok(organizationApi.transferOwnership(
                new TransferOrganizationOwnershipCommand(
                        PlatformHttpSupport.userId(authentication), organizationId,
                        request.newOwnerUserId())));
    }

    @PostMapping("/{organizationId}/leave")
    public ApiResponse<OrganizationLeaveView> leaveOrganization(
            @PathVariable String organizationId,
            Authentication authentication) {
        // A client may switch to another Organization first, then leave the old one.
        return ApiResponse.ok(organizationApi.leaveOrganization(new LeaveOrganizationCommand(
                PlatformHttpSupport.userId(authentication), organizationId)));
    }

    private static void requireActiveContext(
            String organizationId,
            Authentication authentication) {
        if (!organizationId.equals(PlatformHttpSupport.tenantId(authentication))) {
            throw new BusinessException(
                    "Switch to the Organization before managing it",
                    HttpStatus.CONFLICT,
                    "ORGANIZATION_CONTEXT_MISMATCH");
        }
    }

    public record CreateOrganizationRequest(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 63) String slug) {
    }

    public record AddMemberRequest(
            @NotBlank String userId,
            @NotNull TenantRole role) {
    }

    public record TransferOwnerRequest(@NotBlank String newOwnerUserId) {
    }
}
