package com.spaceagent.platform.identity.domain;

import java.util.Comparator;
import java.util.List;

public enum UserCleanupStepKey {
    AUTH_FREEZE(100),
    AUTOMATION_FREEZE_PURGE(200),
    RUNTIME_QUIESCE(300),
    OWNERSHIP_MEMBERSHIP_RESOLUTION(400),
    ARTIFACT_PURGE(500),
    RUNTIME_PURGE(600),
    CONVERSATION_PRIVATE_PURGE(700),
    USER_MEMORY_PURGE(800),
    KNOWLEDGE_PRIVATE_PURGE(900),
    PROJECT_PRIVATE_RESOURCE_PURGE(1000),
    AGENT_PRIVATE_RESOURCE_PURGE(1100),
    INFERENCE_PRIVATE_RESOURCE_PURGE(1200),
    TOOLING_PRIVATE_RESOURCE_PURGE(1300),
    GOVERNANCE_PRIVATE_RESOURCE_PURGE(1400),
    IDENTITY_FINALIZE_USER_TOMBSTONE(1500);

    private final int sequence;

    UserCleanupStepKey(int sequence) {
        this.sequence = sequence;
    }

    public int sequence() {
        return sequence;
    }

    public static List<UserCleanupStepKey> ordered() {
        return java.util.Arrays.stream(values())
                .sorted(Comparator.comparingInt(UserCleanupStepKey::sequence))
                .toList();
    }
}
