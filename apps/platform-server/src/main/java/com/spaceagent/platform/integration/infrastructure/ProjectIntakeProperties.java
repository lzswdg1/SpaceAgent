package com.spaceagent.platform.integration.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "platform.project.intake")
public class ProjectIntakeProperties {
    private boolean workerEnabled = true;
    private String workerId;
    private long pollDelayMs = 1_000;
    private int leaseSeconds = 900;
    private int maximumAttempts = 3;

    public boolean isWorkerEnabled() { return workerEnabled; }
    public void setWorkerEnabled(boolean workerEnabled) { this.workerEnabled = workerEnabled; }
    public String getWorkerId() { return workerId; }
    public void setWorkerId(String workerId) { this.workerId = workerId; }
    public long getPollDelayMs() { return pollDelayMs; }
    public void setPollDelayMs(long value) {
        if (value < 250 || value > 300_000) throw new IllegalArgumentException("Invalid intake poll delay");
        pollDelayMs = value;
    }
    public int getLeaseSeconds() { return leaseSeconds; }
    public void setLeaseSeconds(int value) {
        if (value < 60 || value > 1_800) throw new IllegalArgumentException("Invalid intake lease");
        leaseSeconds = value;
    }
    public int getMaximumAttempts() { return maximumAttempts; }
    public void setMaximumAttempts(int value) {
        if (value < 1 || value > 10) throw new IllegalArgumentException("Invalid intake attempts");
        maximumAttempts = value;
    }
}
