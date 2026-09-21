package com.spaceagent.platform.inference.domain;

public enum ModelCallStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
    UNKNOWN
}
