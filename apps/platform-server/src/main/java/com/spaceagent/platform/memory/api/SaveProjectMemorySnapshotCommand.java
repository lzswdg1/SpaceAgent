package com.spaceagent.platform.memory.api;

import com.spaceagent.platform.memory.domain.MemoryKind;

public record SaveProjectMemorySnapshotCommand(
        String projectId, MemoryKind kind, String key, String value) { }
