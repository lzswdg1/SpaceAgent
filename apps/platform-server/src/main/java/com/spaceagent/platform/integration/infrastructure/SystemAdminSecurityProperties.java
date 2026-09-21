package com.spaceagent.platform.integration.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.system-admin.security")
public class SystemAdminSecurityProperties {
    private String issuer = "spaceagent-platform-admin";
    private String audience = "spaceagent-platform-admin-internal";
    private String jwtSecret = "local-dev-system-admin-jwt-secret-change-me";
    private int maximumTokenSeconds = 60;
    private boolean allowInsecureLocal;

    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public int getMaximumTokenSeconds() { return maximumTokenSeconds; }
    public void setMaximumTokenSeconds(int value) { this.maximumTokenSeconds = value; }
    public boolean isAllowInsecureLocal() { return allowInsecureLocal; }
    public void setAllowInsecureLocal(boolean value) { this.allowInsecureLocal = value; }
}
