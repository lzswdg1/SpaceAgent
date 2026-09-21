package com.spaceagent.platform.runtime.application;

import com.spaceagent.platform.tooling.api.RuntimeCapabilityCatalogApplicationApi.CapabilityView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeToolExposurePolicyTest {
    @Test
    void hidesToolsThatTheExactRunCannotExecute() {
        List<CapabilityView> tools = List.of(
                tool("echo", true, false, false),
                tool("knowledge_search", true, false, false),
                tool("web_search", true, true, false),
                tool("file_read", true, false, true),
                tool("unavailable", false, false, false));

        assertThat(RuntimeToolExposurePolicy.eligibleToolIds(
                tools, false, false, false, false))
                .containsExactly("echo");
        assertThat(RuntimeToolExposurePolicy.eligibleToolIds(
                tools, true, true, true, true))
                .containsExactly("echo", "knowledge_search", "web_search", "file_read");
    }

    private static CapabilityView tool(
            String id, boolean available, boolean requiresNetwork, boolean requiresWorkspace) {
        return new CapabilityView(
                id, id, id, "TEST", Map.of(), available, true,
                requiresNetwork, requiresWorkspace);
    }
}
