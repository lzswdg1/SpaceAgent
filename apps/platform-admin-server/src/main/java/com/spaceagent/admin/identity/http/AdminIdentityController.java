package com.spaceagent.admin.identity.http;

import com.spaceagent.admin.identity.application.AdminAuthenticationService;
import com.spaceagent.admin.identity.application.AdminIdentityView;
import com.spaceagent.admin.shared.AdminApiResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/admin/v1")
public class AdminIdentityController {
    private final AdminAuthenticationService authenticationService;

    public AdminIdentityController(AdminAuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @GetMapping("/me")
    public AdminApiResponse<AdminIdentityView> me(@AuthenticationPrincipal Jwt jwt) {
        return AdminApiResponse.ok(authenticationService.me(UUID.fromString(jwt.getSubject())));
    }
}
