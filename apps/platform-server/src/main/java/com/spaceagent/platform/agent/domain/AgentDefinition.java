package com.spaceagent.platform.agent.domain;

import java.time.Instant;

/**
 * The single stable Agent identity. Runtime-affecting values live in the one mutable
 * AgentCurrentConfiguration and are snapshotted only when a Run starts.
 */
public record AgentDefinition(
        String id,
        String ownerId,
        String tenantId,
        String name,
        String description,
        AgentDefinitionStatus status,
        long revision,
        Instant createdAt,
        Instant updatedAt,
        Instant archivedAt) {

    public AgentDefinition {
        if (revision <= 0) {
            throw new IllegalArgumentException("revision must be positive");
        }
    }

    public AgentDefinition updateMetadata(String updatedName, String updatedDescription, Instant at) {
        return new AgentDefinition(
                id, ownerId, tenantId, updatedName, updatedDescription, status,
                revision + 1, createdAt, at, archivedAt);
    }

    public AgentDefinition archive(Instant at) {
        return new AgentDefinition(
                id, ownerId, tenantId, name, description, AgentDefinitionStatus.ARCHIVED,
                revision + 1, createdAt, at, at);
    }
}
