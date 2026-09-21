package com.spaceagent.platform.observability.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Bounded operator configuration for the SQL-backed Agent metric projection. */
@ConfigurationProperties(prefix = "platform.observability.operational-metrics")
public class AgentOperationalMetricsProperties {

    private Duration window = Duration.ofMinutes(5);
    private long refreshDelayMs = 15_000;

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        if (window == null || window.isZero() || window.isNegative()
                || window.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException(
                    "Operational metrics window must be greater than zero and at most one day");
        }
        this.window = window;
    }

    public long getRefreshDelayMs() {
        return refreshDelayMs;
    }

    public void setRefreshDelayMs(long refreshDelayMs) {
        if (refreshDelayMs < 1_000 || refreshDelayMs > 300_000) {
            throw new IllegalArgumentException(
                    "Operational metrics refresh delay must be between 1000 and 300000 ms");
        }
        this.refreshDelayMs = refreshDelayMs;
    }
}
