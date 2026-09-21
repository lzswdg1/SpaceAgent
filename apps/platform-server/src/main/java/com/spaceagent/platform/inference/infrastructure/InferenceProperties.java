package com.spaceagent.platform.inference.infrastructure;

import com.spaceagent.platform.inference.domain.ModelCallLeasePolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Inference infrastructure configuration. This class intentionally lives in
 * infrastructure because it contains framework configuration, not domain state.
 */
@ConfigurationProperties(prefix = "platform.inference")
public class InferenceProperties implements ModelCallLeasePolicy {

    private String modelProviderEncryptionKey = "local-dev-model-provider-encryption-key-change-me";
    private List<String> modelProviderPreviousEncryptionKeys = new ArrayList<>();
    private List<String> allowedProviderHosts = new ArrayList<>(List.of(
            "dashscope.aliyuncs.com",
            "*.maas.aliyuncs.com",
            "api.deepseek.com",
            "api.openai.com"
    ));
    private boolean allowLocalProviderHosts;
    private long connectTimeoutSeconds = 10;
    private long requestTimeoutSeconds = 120;
    private long connectionTestTimeoutSeconds = 15;
    private long claimLeaseSafetySeconds = 30;
    private boolean healthProbesEnabled = true;
    private String healthProbeWorkerId;
    private long healthProbeIntervalSeconds = 300;
    private long healthProbeRetryBaseSeconds = 30;
    private long healthProbeLeaseSeconds = 60;
    private int healthProbeBatchSize = 10;

    public String getModelProviderEncryptionKey() {
        return modelProviderEncryptionKey;
    }

    public void setModelProviderEncryptionKey(String modelProviderEncryptionKey) {
        this.modelProviderEncryptionKey = modelProviderEncryptionKey;
    }

    public List<String> getModelProviderPreviousEncryptionKeys() {
        return modelProviderPreviousEncryptionKeys;
    }

    public void setModelProviderPreviousEncryptionKeys(List<String> modelProviderPreviousEncryptionKeys) {
        this.modelProviderPreviousEncryptionKeys = modelProviderPreviousEncryptionKeys;
    }

    public List<String> getAllowedProviderHosts() {
        return allowedProviderHosts;
    }

    public void setAllowedProviderHosts(List<String> allowedProviderHosts) {
        this.allowedProviderHosts = allowedProviderHosts == null
                ? new ArrayList<>()
                : new ArrayList<>(allowedProviderHosts);
    }

    public boolean isAllowLocalProviderHosts() {
        return allowLocalProviderHosts;
    }

    public void setAllowLocalProviderHosts(boolean allowLocalProviderHosts) {
        this.allowLocalProviderHosts = allowLocalProviderHosts;
    }

    public long getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(long connectTimeoutSeconds) {
        this.connectTimeoutSeconds = bounded("connectTimeoutSeconds", connectTimeoutSeconds, 60);
    }

    public long getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(long requestTimeoutSeconds) {
        this.requestTimeoutSeconds = bounded("requestTimeoutSeconds", requestTimeoutSeconds, 600);
    }

    public long getClaimLeaseSafetySeconds() {
        return claimLeaseSafetySeconds;
    }

    public long getConnectionTestTimeoutSeconds() {
        return connectionTestTimeoutSeconds;
    }

    public void setConnectionTestTimeoutSeconds(long connectionTestTimeoutSeconds) {
        this.connectionTestTimeoutSeconds = bounded(
                "connectionTestTimeoutSeconds", connectionTestTimeoutSeconds, 60);
    }

    public void setClaimLeaseSafetySeconds(long claimLeaseSafetySeconds) {
        this.claimLeaseSafetySeconds = bounded("claimLeaseSafetySeconds", claimLeaseSafetySeconds, 120);
    }

    public boolean isHealthProbesEnabled() { return healthProbesEnabled; }
    public void setHealthProbesEnabled(boolean value) { healthProbesEnabled = value; }
    public String getHealthProbeWorkerId() { return healthProbeWorkerId; }
    public void setHealthProbeWorkerId(String value) { healthProbeWorkerId = value; }
    public long getHealthProbeIntervalSeconds() { return healthProbeIntervalSeconds; }
    public void setHealthProbeIntervalSeconds(long value) {
        healthProbeIntervalSeconds = bounded("healthProbeIntervalSeconds", value, 86400);
    }
    public long getHealthProbeRetryBaseSeconds() { return healthProbeRetryBaseSeconds; }
    public void setHealthProbeRetryBaseSeconds(long value) {
        healthProbeRetryBaseSeconds = bounded("healthProbeRetryBaseSeconds", value, 3600);
    }
    public long getHealthProbeLeaseSeconds() { return healthProbeLeaseSeconds; }
    public void setHealthProbeLeaseSeconds(long value) {
        healthProbeLeaseSeconds = bounded("healthProbeLeaseSeconds", value, 600);
    }
    public int getHealthProbeBatchSize() { return healthProbeBatchSize; }
    public void setHealthProbeBatchSize(int value) {
        if (value < 1 || value > 100) throw new IllegalArgumentException(
                "healthProbeBatchSize must be between 1 and 100");
        healthProbeBatchSize = value;
    }

    @Override
    public long requestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    @Override
    public long claimLeaseSeconds() {
        return requestTimeoutSeconds + claimLeaseSafetySeconds;
    }

    private static long bounded(String name, long value, long maximum) {
        if (value <= 0 || value > maximum) {
            throw new IllegalArgumentException(name + " must be between 1 and " + maximum);
        }
        return value;
    }
}
