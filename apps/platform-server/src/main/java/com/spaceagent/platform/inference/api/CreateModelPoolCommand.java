package com.spaceagent.platform.inference.api;

import com.spaceagent.platform.inference.domain.ModelPoolRoutingStrategy;
import com.spaceagent.platform.inference.domain.ModelPoolVisibility;

public record CreateModelPoolCommand(
        String tenantId,
        String ownerId,
        String name,
        ModelPoolVisibility visibility,
        ModelPoolRoutingStrategy routingStrategy,
        boolean fallbackEnabled) {
}
