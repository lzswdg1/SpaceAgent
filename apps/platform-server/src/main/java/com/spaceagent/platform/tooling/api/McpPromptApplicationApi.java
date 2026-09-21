package com.spaceagent.platform.tooling.api;

import java.util.List;
import java.util.Map;

public interface McpPromptApplicationApi {
    List<PromptView> list(ListCommand command);

    PromptResult get(GetCommand command);

    record Binding(
            String installationId,
            String connectionId,
            String serverVersionId,
            String capabilitySnapshotId,
            long connectionRevision,
            String snapshotSha256,
            List<String> allowedToolNames) {
    }

    record ListCommand(String tenantId, String ownerUserId, Binding binding) {
    }

    record GetCommand(
            String tenantId,
            String ownerUserId,
            Binding binding,
            String promptName,
            Map<String, String> arguments) {
    }

    record PromptView(
            String name,
            String title,
            String description,
            List<ArgumentView> arguments) {
    }

    record ArgumentView(String name, String title, String description, boolean required) {
    }

    record PromptResult(String description, List<MessageView> messages, int utf8Bytes) {
    }

    record MessageView(String role, String text, int utf8Bytes) {
    }
}
