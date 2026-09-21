package com.spaceagent.platform.runtime.domain;

public class MultiAgentOrchestrationUnavailableException extends RuntimeException {

    public MultiAgentOrchestrationUnavailableException(String message) {
        super(message);
    }

    public MultiAgentOrchestrationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
