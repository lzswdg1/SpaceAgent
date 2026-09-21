package com.spaceagent.platform.tooling.domain;

public class McpRegistryGatewayException extends RuntimeException {
    private final String safeCode;

    public McpRegistryGatewayException(String safeCode, String message) {
        super(message);
        this.safeCode = safeCode;
    }

    public McpRegistryGatewayException(String safeCode, String message, Throwable cause) {
        super(message, cause);
        this.safeCode = safeCode;
    }

    public String safeCode() {
        return safeCode;
    }
}
