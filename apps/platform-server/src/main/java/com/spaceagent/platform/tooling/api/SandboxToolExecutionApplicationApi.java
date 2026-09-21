package com.spaceagent.platform.tooling.api;

/**
 * Public tool execution application API. Runtime consumes this boundary instead of
 * reaching into tooling persistence or the sandbox gateway directly.
 */
public interface SandboxToolExecutionApplicationApi {

    SandboxToolExecutionView execute(SandboxToolExecutionCommand command);
}
