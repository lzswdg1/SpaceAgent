package com.spaceagent.platform.artifact.domain;

/** A deletion permission won; new references/holds cannot promise retained bytes. */
public class ArtifactObjectLifecycleConflictException extends IllegalStateException {
    public ArtifactObjectLifecycleConflictException() { super("Artifact publication no longer admits references or holds"); }
}
