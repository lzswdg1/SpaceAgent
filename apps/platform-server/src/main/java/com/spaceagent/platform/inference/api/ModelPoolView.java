package com.spaceagent.platform.inference.api;

import com.spaceagent.platform.inference.domain.ModelPoolRoutingStrategy;
import com.spaceagent.platform.inference.domain.ModelPoolStatus;
import com.spaceagent.platform.inference.domain.ModelPoolVisibility;

import java.time.Instant;

public record ModelPoolView(
        String id,
        String tenantId,
        String ownerId,
        String name,
        ModelPoolVisibility visibility,
        ModelPoolRoutingStrategy routingStrategy,
        boolean fallbackEnabled,
        ModelPoolStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
