package com.spaceagent.admin.security;

import java.time.Instant;

public record AdminAccessToken(String value, Instant expiresAt) {
}
