package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.identity.api.IdentityActivationApplicationApi;
import com.spaceagent.platform.integration.infrastructure.PlatformRequestAdmissionService;
import com.spaceagent.platform.integration.infrastructure.PlatformTokenIssuer;
import com.spaceagent.shared.api.ApiResponse;
import com.spaceagent.shared.auth.AuthToken;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PlatformIdentityActivationHttpController {
    private final IdentityActivationApplicationApi activationApi;
    private final PlatformTokenIssuer tokenIssuer;
    private final PlatformRequestAdmissionService admission;

    public PlatformIdentityActivationHttpController(
            IdentityActivationApplicationApi activationApi,
            PlatformTokenIssuer tokenIssuer,
            PlatformRequestAdmissionService admission) {
        this.activationApi = activationApi;
        this.tokenIssuer = tokenIssuer;
        this.admission = admission;
    }

    @PostMapping("/activate")
    public ApiResponse<AuthToken> activate(
            @Valid @RequestBody ActivationRequest request,
            HttpServletRequest httpRequest) {
        admission.requireAuthenticationAttempt("activate", request.activationToken(), httpRequest, 10);
        return ApiResponse.ok(tokenIssuer.issue(
                activationApi.activate(request.activationToken(), request.password())));
    }

    public record ActivationRequest(
            @NotBlank @Size(max = 128) String activationToken,
            @NotBlank @Size(min = 10, max = 64) String password) {
    }
}
