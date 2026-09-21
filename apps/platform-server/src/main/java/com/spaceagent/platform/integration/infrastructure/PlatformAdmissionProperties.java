package com.spaceagent.platform.integration.infrastructure;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Deployment resource ceilings only; never authorization or durable business state. */
@Validated
@ConfigurationProperties(prefix = "platform.admission")
public class PlatformAdmissionProperties {
    @Min(1) @Max(4096) private int maxSseConnections = 256;
    @Min(1) @Max(1024) private int maxSseConnectionsPerUser = 4;
    @Min(100) @Max(100000) private int maxRateKeys = 20000;

    public void validate() {
        if (maxSseConnections < 1 || maxSseConnections > 4096 || maxSseConnectionsPerUser < 1
                || maxSseConnectionsPerUser > 1024 || maxSseConnectionsPerUser > maxSseConnections
                || maxRateKeys < 100 || maxRateKeys > 100000) {
            throw new IllegalArgumentException("Invalid process admission resource ceilings");
        }
    }
    public int getMaxSseConnections() { return maxSseConnections; }
    public void setMaxSseConnections(int value) { maxSseConnections = value; }
    public int getMaxSseConnectionsPerUser() { return maxSseConnectionsPerUser; }
    public void setMaxSseConnectionsPerUser(int value) { maxSseConnectionsPerUser = value; }
    public int getMaxRateKeys() { return maxRateKeys; }
    public void setMaxRateKeys(int value) { maxRateKeys = value; }
}
