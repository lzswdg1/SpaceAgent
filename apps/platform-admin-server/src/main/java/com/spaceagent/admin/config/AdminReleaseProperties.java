package com.spaceagent.admin.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "admin.release")
public class AdminReleaseProperties {

    public enum Mode {
        DEVELOPMENT,
        TRUSTED_BETA,
        PRODUCTION
    }

    private Mode mode = Mode.DEVELOPMENT;

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode == null ? Mode.DEVELOPMENT : mode;
    }

    public boolean requiresProductionSecurity() {
        return mode == Mode.TRUSTED_BETA || mode == Mode.PRODUCTION;
    }
}
