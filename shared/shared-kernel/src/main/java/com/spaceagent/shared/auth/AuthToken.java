package com.spaceagent.shared.auth;

import java.time.Instant;

/**
 * Token 信息载体，包含 JWT token 字符串和从 claims 中提取的用户身份信息。
 */
public record AuthToken(
        String token,
        String refreshToken,
        String userId,
        String username,
        String role,
        String tenantId,
        String tenantRole,
        Instant expiresAt,
        Instant refreshExpiresAt
) {
    public AuthToken(String token, String userId, String username, String role) {
        this(token, null, userId, username, role, userId, "OWNER", null, null);
    }
}
