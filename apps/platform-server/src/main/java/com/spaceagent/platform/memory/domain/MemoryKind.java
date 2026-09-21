package com.spaceagent.platform.memory.domain;

/**
 * The kind of durable knowledge represented by a memory item.
 */
public enum MemoryKind {
    FACT,
    PREFERENCE,
    CONSTRAINT,
    DECISION,
    ARCHITECTURE,
    PROCEDURE,
    FAILURE,
    EXPERIENCE
}
