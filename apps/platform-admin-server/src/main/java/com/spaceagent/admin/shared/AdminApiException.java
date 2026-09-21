package com.spaceagent.admin.shared;

import org.springframework.http.HttpStatus;

public class AdminApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public AdminApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
}
