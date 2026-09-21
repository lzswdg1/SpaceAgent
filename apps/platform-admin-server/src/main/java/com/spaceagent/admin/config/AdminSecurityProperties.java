package com.spaceagent.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;


@ConfigurationProperties(prefix = "admin.security")
public class AdminSecurityProperties {
    private String issuer = "spaceagent-platform-admin";
    private String audience = "spaceagent-platform-admin-api";
    private String jwtSecret = "local-dev-admin-jwt-secret-change-in-production";
    private int accessTokenMinutes = 10;
    private int refreshTokenDays = 7;
    private int maxLoginAttempts = 5;
    private boolean cookieSecure = true;

    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public int getAccessTokenMinutes() { return accessTokenMinutes; }
    public void setAccessTokenMinutes(int value) { this.accessTokenMinutes = value; }
    public int getRefreshTokenDays() { return refreshTokenDays; }
    public void setRefreshTokenDays(int value) { this.refreshTokenDays = value; }
    public int getMaxLoginAttempts() { return maxLoginAttempts; }
    public void setMaxLoginAttempts(int value) { this.maxLoginAttempts = value; }
    public boolean isCookieSecure() { return cookieSecure; }
    public void setCookieSecure(boolean value) { this.cookieSecure = value; }
}
