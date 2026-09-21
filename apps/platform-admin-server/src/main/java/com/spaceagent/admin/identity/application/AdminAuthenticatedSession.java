package com.spaceagent.admin.identity.application;

import java.time.Instant;

public record AdminAuthenticatedSession(
        String accessToken,
        Instant accessTokenExpiresAt,
        String refreshToken,
        String csrfToken,
        Instant refreshTokenExpiresAt,
        AdminIdentityView administrator) {
}
