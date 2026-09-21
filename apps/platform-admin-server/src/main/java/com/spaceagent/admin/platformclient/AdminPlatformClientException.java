package com.spaceagent.admin.platformclient;

public class AdminPlatformClientException extends RuntimeException {
    private final String safeCode;

    public AdminPlatformClientException(String safeCode, String message) {
        super(message);
        this.safeCode = safeCode;
    }

    public AdminPlatformClientException(String safeCode, String message, Throwable cause) {
        super(message, cause);
        this.safeCode = safeCode;
    }

    public String safeCode() { return safeCode; }
}
