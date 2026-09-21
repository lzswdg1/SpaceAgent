package com.spaceagent.platform.project.api;

/** Raw Bridge token is returned exactly once. */
public record CreatedLocalWorkspaceBridgeView(
        String bridgeToken, LocalWorkspaceBridgeView bridge) { }
