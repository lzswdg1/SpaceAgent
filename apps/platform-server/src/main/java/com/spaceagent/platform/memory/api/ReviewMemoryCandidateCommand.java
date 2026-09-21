package com.spaceagent.platform.memory.api;

import com.spaceagent.platform.memory.domain.MemoryCandidateState;

/**
 * Public command for accepting or rejecting a memory candidate.
 */
public record ReviewMemoryCandidateCommand(
        String candidateId,
        MemoryCandidateState decision,
        String reason) {

    public ReviewMemoryCandidateCommand {
        requireNonBlank(candidateId, "candidateId");
        if (decision != MemoryCandidateState.ACCEPTED && decision != MemoryCandidateState.REJECTED) {
            throw new IllegalArgumentException("decision must be ACCEPTED or REJECTED");
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
