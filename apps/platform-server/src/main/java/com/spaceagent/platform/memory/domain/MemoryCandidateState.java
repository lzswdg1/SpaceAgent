package com.spaceagent.platform.memory.domain;

/**
 * Lifecycle state of a memory candidate before consolidation.
 */
public enum MemoryCandidateState {
    PENDING,
    ACCEPTED,
    REJECTED,
    CONSOLIDATED,
    EXPIRED
}
