package com.spaceagent.platform.integration.infrastructure.http;

import com.spaceagent.platform.identity.api.IdentityPasswordResetApi;
import com.spaceagent.platform.integration.infrastructure.PlatformRequestAdmissionService;
import com.spaceagent.shared.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(prefix = "platform", name = "persistence", havingValue = "postgres")
public class PlatformPasswordResetHttpController {
    private final IdentityPasswordResetApi resets;
    private final PlatformRequestAdmissionService admission;
    public PlatformPasswordResetHttpController(IdentityPasswordResetApi resets, PlatformRequestAdmissionService admission) {
        this.resets = resets;
        this.admission = admission;
    }
    @PostMapping("/api/v1/auth/password-reset")
    public ApiResponse<Void> reset(@Valid @RequestBody ResetRequest request, HttpServletRequest httpRequest) {
        admission.requireAuthenticationAttempt("password-reset", request.resetToken(), httpRequest, 10);
        resets.resetPassword(request.resetToken(), request.password());
        return ApiResponse.ok(null);
    }
    public record ResetRequest(@NotBlank @Size(max=200) String resetToken,
                               @NotBlank @Size(min=10,max=64) String password) { }
}
