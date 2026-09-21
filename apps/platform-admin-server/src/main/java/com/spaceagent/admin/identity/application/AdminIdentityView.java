package com.spaceagent.admin.identity.application;

import com.spaceagent.admin.identity.domain.SystemAdministrator;

import java.time.Instant;
import java.util.UUID;

public record AdminIdentityView(
        UUID id,
        String loginName,
        String displayName,
        String role,
        String status,
        boolean mustChangePassword,
        Instant lastSuccessfulLoginAt) {

    public static AdminIdentityView from(SystemAdministrator value) {
        return new AdminIdentityView(value.id(), value.loginName(), value.displayName(),
                value.role().name(), value.status().name(), value.mustChangePassword(),
                value.lastSuccessfulLoginAt());
    }
}
