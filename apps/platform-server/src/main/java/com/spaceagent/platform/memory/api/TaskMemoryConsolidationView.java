package com.spaceagent.platform.memory.api;

import java.util.List;

/**
 * Result of task-completion memory consolidation.
 */
public record TaskMemoryConsolidationView(
        String taskId,
        int accepted,
        int rejected,
        int consolidatedTaskMemories,
        int promotedToProject,
        int promotedToUser,
        List<ScopedMemoryView> consolidatedMemories) {

    public TaskMemoryConsolidationView {
        consolidatedMemories = consolidatedMemories == null
                ? List.of()
                : List.copyOf(consolidatedMemories);
    }
}
