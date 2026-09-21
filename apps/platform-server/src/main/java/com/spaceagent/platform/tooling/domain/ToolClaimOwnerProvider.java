package com.spaceagent.platform.tooling.domain;

/**
 * Supplies a JVM-stable diagnostic owner identifier for tool claims.
 */
@FunctionalInterface
public interface ToolClaimOwnerProvider {
    String ownerId();
}
