package com.spaceagent.platform.integration.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.release")
public class PlatformReleaseProperties {
    public enum Mode { DEVELOPMENT, TRUSTED_BETA }

    private Mode mode = Mode.DEVELOPMENT;
    private String version = "dev";
    private int expectedSchemaVersion = 1098;
    private boolean trustedCodeOnly = true;
    private boolean publicUntrustedCodeEnabled;

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public int getExpectedSchemaVersion() { return expectedSchemaVersion; }
    public void setExpectedSchemaVersion(int expectedSchemaVersion) {
        if (expectedSchemaVersion <= 0) {
            throw new IllegalArgumentException("expectedSchemaVersion must be positive");
        }
        this.expectedSchemaVersion = expectedSchemaVersion;
    }
    public boolean isTrustedCodeOnly() { return trustedCodeOnly; }
    public void setTrustedCodeOnly(boolean trustedCodeOnly) { this.trustedCodeOnly = trustedCodeOnly; }
    public boolean isPublicUntrustedCodeEnabled() { return publicUntrustedCodeEnabled; }
    public void setPublicUntrustedCodeEnabled(boolean value) { publicUntrustedCodeEnabled = value; }
}
