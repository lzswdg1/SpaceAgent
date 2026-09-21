package com.spaceagent.platform.runtime.application;

import com.spaceagent.platform.tooling.api.RuntimeCapabilityCatalogApplicationApi.CapabilityView;

import java.util.List;

/** Filters configured Tools to the subset this exact Run can truthfully offer to a model. */
final class RuntimeToolExposurePolicy {
    private RuntimeToolExposurePolicy() {
    }

    static List<String> eligibleToolIds(
            List<CapabilityView> configuredTools,
            boolean ragEnabled,
            boolean hasKnowledgeBindings,
            boolean networkEnabled,
            boolean workspaceBound) {
        if (configuredTools == null || configuredTools.isEmpty()) {
            return List.of();
        }
        return configuredTools.stream()
                .filter(CapabilityView::available)
                .filter(tool -> !tool.requiresNetwork() || networkEnabled)
                .filter(tool -> !tool.requiresWorkspace() || workspaceBound)
                .filter(tool -> !"knowledge_search".equals(tool.id())
                        || (ragEnabled && hasKnowledgeBindings))
                .map(CapabilityView::id)
                .distinct()
                .toList();
    }
}
