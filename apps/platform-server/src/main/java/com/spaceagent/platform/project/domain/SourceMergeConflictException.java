package com.spaceagent.platform.project.domain;

public class SourceMergeConflictException extends RuntimeException {
    private final String code;

    public SourceMergeConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
