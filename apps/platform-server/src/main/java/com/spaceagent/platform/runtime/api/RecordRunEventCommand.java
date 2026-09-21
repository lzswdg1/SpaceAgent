package com.spaceagent.platform.runtime.api;

import com.spaceagent.platform.runtime.domain.RunEventType;

/** Records a validated external boundary event in the Java-owned ledger. */
public record RecordRunEventCommand(
        String agentRunId,
        RunEventType type,
        String payload) {

    public RecordRunEventCommand {
        if (agentRunId == null || agentRunId.isBlank()) {
            throw new IllegalArgumentException("agentRunId is required");
        }
        if (type == null) {
            throw new IllegalArgumentException("type is required");
        }
        payload = payload == null || payload.isBlank() ? "{}" : payload;
    }
}
