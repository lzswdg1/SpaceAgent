package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.identity.api.AcceptOrganizationInvitationCommand;
import com.spaceagent.platform.identity.api.AcceptedOrganizationInvitationView;
import com.spaceagent.platform.identity.api.CreateOrganizationInvitationCommand;
import com.spaceagent.platform.identity.api.OrganizationInvitationApplicationApi;
import com.spaceagent.platform.identity.api.OrganizationInvitationCreatedView;
import com.spaceagent.platform.identity.api.OrganizationInvitationPreviewView;
import com.spaceagent.platform.identity.api.OrganizationInvitationView;
import com.spaceagent.platform.identity.api.RevokeOrganizationInvitationCommand;
import com.spaceagent.platform.identity.domain.TenantRole;
import com.spaceagent.shared.api.ApiResponse;
import com.spaceagent.platform.integration.infrastructure.PlatformRequestAdmissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
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

/** HTTP adapter for Identity-owned Organization invitation state. */
@RestController
@RequestMapping("/api/v1")
public class PlatformOrganizationInvitationHttpController {

    private final OrganizationInvitationApplicationApi invitations;
    private final PlatformRequestAdmissionService admission;

    public PlatformOrganizationInvitationHttpController(
            OrganizationInvitationApplicationApi invitations,
            PlatformRequestAdmissionService admission) {
        this.invitations = invitations;
        this.admission = admission;
    }

    @PostMapping("/organizations/{organizationId}/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<OrganizationInvitationCreatedView> create(
            @PathVariable String organizationId,
            @Valid @RequestBody CreateInvitationRequest request,
            Authentication authentication) {
        return ApiResponse.ok(invitations.createInvitation(
                new CreateOrganizationInvitationCommand(
                        organizationId,
                        PlatformHttpSupport.userId(authentication),
                        request.email(),
                        request.role(),
                        request.expiresInHours())));
    }

    @GetMapping("/organizations/{organizationId}/invitations")
    public ApiResponse<List<OrganizationInvitationView>> list(
            @PathVariable String organizationId,
            Authentication authentication) {
        return ApiResponse.ok(invitations.listInvitations(
                organizationId,
                PlatformHttpSupport.userId(authentication)));
    }

    @DeleteMapping("/organizations/{organizationId}/invitations/{invitationId}")
    public ApiResponse<OrganizationInvitationView> revoke(
            @PathVariable String organizationId,
            @PathVariable String invitationId,
            Authentication authentication) {
        return ApiResponse.ok(invitations.revokeInvitation(
                new RevokeOrganizationInvitationCommand(
                        organizationId,
                        invitationId,
                        PlatformHttpSupport.userId(authentication))));
    }

    @PostMapping("/public/organization-invitations/preview")
    public ApiResponse<OrganizationInvitationPreviewView> preview(
            @Valid @RequestBody InvitationTokenRequest request,
            HttpServletRequest httpRequest) {
        admission.requireAuthenticationAttempt("invitation-preview", request.token(), httpRequest, 20);
        return ApiResponse.ok(invitations.previewInvitation(request.token()));
    }

    @PostMapping("/organization-invitations/accept")
    public ApiResponse<AcceptedOrganizationInvitationView> accept(
            @Valid @RequestBody InvitationTokenRequest request,
            Authentication authentication) {
        return ApiResponse.ok(invitations.acceptInvitation(
                new AcceptOrganizationInvitationCommand(
                        request.token(),
                        PlatformHttpSupport.userId(authentication))));
    }

    public record CreateInvitationRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotNull TenantRole role,
            @Min(1) @Max(720) Long expiresInHours) {
    }

    public record InvitationTokenRequest(
            @NotBlank @Size(min = 32, max = 512) String token) {
    }
}
