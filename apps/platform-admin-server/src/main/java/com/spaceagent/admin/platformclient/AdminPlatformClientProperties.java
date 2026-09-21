package com.spaceagent.admin.platformclient;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "admin.platform-client")
public class AdminPlatformClientProperties {
    private String baseUrl = "http://127.0.0.1:9000";
    private String issuer = "spaceagent-platform-admin";
    private String audience = "spaceagent-platform-admin-internal";
    private String serviceId = "platform-admin-server";
    private String jwtSecret = "local-dev-system-admin-jwt-secret-change-me";
    private int tokenSeconds = 45;
    private int connectTimeoutSeconds = 3;
    private int requestTimeoutSeconds = 10;
    private int maximumResponseBytes = 2_000_000;
    private boolean allowInsecureLocal;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public String getIssuer() { return issuer; }
    public void setIssuer(String value) { issuer = value; }
    public String getAudience() { return audience; }
    public void setAudience(String value) { audience = value; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String value) { serviceId = value; }
    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String value) { jwtSecret = value; }
    public int getTokenSeconds() { return tokenSeconds; }
    public void setTokenSeconds(int value) { tokenSeconds = value; }
    public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
    public void setConnectTimeoutSeconds(int value) { connectTimeoutSeconds = value; }
    public int getRequestTimeoutSeconds() { return requestTimeoutSeconds; }
    public void setRequestTimeoutSeconds(int value) { requestTimeoutSeconds = value; }
    public int getMaximumResponseBytes() { return maximumResponseBytes; }
    public void setMaximumResponseBytes(int value) { maximumResponseBytes = value; }
    public boolean isAllowInsecureLocal() { return allowInsecureLocal; }
    public void setAllowInsecureLocal(boolean value) { allowInsecureLocal = value; }
}
