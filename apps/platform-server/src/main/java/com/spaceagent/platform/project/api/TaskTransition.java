package com.spaceagent.platform.project.api;

/** Explicit Task lifecycle actions exposed by the Application API. */
public enum TaskTransition {
    MARK_READY,
    START,
    BLOCK,
    COMPLETE,
    FAIL,
    CANCEL
}
