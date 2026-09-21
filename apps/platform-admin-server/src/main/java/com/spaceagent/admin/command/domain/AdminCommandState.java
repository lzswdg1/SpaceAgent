package com.spaceagent.admin.command.domain;

public enum AdminCommandState {
    RECEIVED,
    DISPATCHING,
    ACCEPTED,
    SUCCEEDED,
    FAILED,
    UNKNOWN
}
