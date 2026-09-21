package com.spaceagent.platform.runtime.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@ConfigurationProperties(prefix = "platform.runtime.coordination")
@Validated
public class RuntimeCoordinationProperties {

    private boolean enabled = true;
    @Size(max = 160)
    private String workerId;
    @Min(1)
    @Max(300)
    private int leaseSeconds = 30;
    @Min(0)
    @Max(3600)
    private int retryDelaySeconds = 5;
    @Min(100)
    @Max(60000)
    private long pollDelayMs = 500;
    @Min(1)
    @Max(100)
    private int batchSize = 8;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public int getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(int leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }

    public int getRetryDelaySeconds() {
        return retryDelaySeconds;
    }

    public void setRetryDelaySeconds(int retryDelaySeconds) {
        this.retryDelaySeconds = retryDelaySeconds;
    }

    public long getPollDelayMs() {
        return pollDelayMs;
    }

    public void setPollDelayMs(long pollDelayMs) {
        this.pollDelayMs = pollDelayMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
