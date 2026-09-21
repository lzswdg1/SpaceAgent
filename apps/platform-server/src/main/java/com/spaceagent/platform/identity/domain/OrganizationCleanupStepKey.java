package com.spaceagent.platform.identity.domain;

import java.util.Comparator;
import java.util.List;

/** Accepted ADR-025 cleanup order. No step performs deletion inside Identity. */
public enum OrganizationCleanupStepKey {
    AUTOMATION_FREEZE_PURGE(100),
    RUNTIME_QUIESCE(200),
    ARTIFACT_PURGE(300),
    RUNTIME_PURGE(400),
    CONVERSATION_PURGE(500),
    TOOLING_CONFIGURATION_PURGE(550),
    PROJECT_TASK_MEMORY_PURGE(600),
    KNOWLEDGE_PURGE(650),
    PROJECT_EXTERNAL_AND_DATABASE_PURGE(700),
    AGENT_PURGE(800),
    INFERENCE_PURGE(900),
    GOVERNANCE_PURGE(1000),
    IDENTITY_FINALIZE(1100);

    private final int sequence;

    OrganizationCleanupStepKey(int sequence) {
        this.sequence = sequence;
    }

    public int sequence() {
        return sequence;
    }

    public static List<OrganizationCleanupStepKey> ordered() {
        return java.util.Arrays.stream(values())
                .sorted(Comparator.comparingInt(OrganizationCleanupStepKey::sequence))
                .toList();
    }
}
