package com.spaceagent.platform.integration.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * HTTP security configuration for the platform-server public edge.
 */
@ConfigurationProperties(prefix = "platform.security")
public class PlatformSecurityProperties {

    private String jwtSecret = "local-dev-jwt-secret-should-be-overridden-12345";
    private String internalToken = "local-dev-internal-token-change-me";
    private int jwtExpirationHours = 24;
    private int refreshTokenExpirationDays = 30;
    private boolean allowInsecureLocal;

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public int getJwtExpirationHours() {
        return jwtExpirationHours;
    }

    public void setJwtExpirationHours(int jwtExpirationHours) {
        this.jwtExpirationHours = jwtExpirationHours;
    }

    public int getRefreshTokenExpirationDays() {
        return refreshTokenExpirationDays;
    }

    public void setRefreshTokenExpirationDays(int refreshTokenExpirationDays) {
        this.refreshTokenExpirationDays = refreshTokenExpirationDays;
    }

    public boolean isAllowInsecureLocal() {
        return allowInsecureLocal;
    }

    public void setAllowInsecureLocal(boolean allowInsecureLocal) {
        this.allowInsecureLocal = allowInsecureLocal;
    }
}
