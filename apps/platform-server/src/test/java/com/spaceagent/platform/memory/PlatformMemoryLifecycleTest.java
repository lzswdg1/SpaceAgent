package com.spaceagent.platform.memory;

import com.spaceagent.platform.memory.api.CompleteTaskMemoryConsolidationCommand;
import com.spaceagent.platform.memory.api.MemoryApplicationApi;
import com.spaceagent.platform.memory.api.MemoryCandidateView;
import com.spaceagent.platform.memory.api.MemoryRecallCommand;
import com.spaceagent.platform.memory.api.ProposeMemoryCandidateCommand;
import com.spaceagent.platform.memory.api.ReviewMemoryCandidateCommand;
import com.spaceagent.platform.memory.api.TaskMemoryConsolidationView;
import com.spaceagent.platform.memory.application.MemoryApplicationService;
import com.spaceagent.platform.memory.domain.MemoryCandidateState;
import com.spaceagent.platform.memory.domain.MemoryKind;
import com.spaceagent.platform.memory.domain.MemoryScope;
import com.spaceagent.platform.memory.domain.MemoryScopeRef;
import com.spaceagent.platform.memory.infrastructure.memory.InMemoryConsolidatedMemoryRepository;
import com.spaceagent.platform.memory.infrastructure.memory.InMemoryMemoryCandidateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformMemoryLifecycleTest {

    private MemoryApplicationApi memoryApi;
    private AtomicInteger ids;

    @BeforeEach
    void setUp() {
        ids = new AtomicInteger();
        memoryApi = new MemoryApplicationService(
                new InMemoryMemoryCandidateRepository(),
                new InMemoryConsolidatedMemoryRepository(),
                () -> "id-" + ids.incrementAndGet(),
                () -> Instant.parse("2026-08-18T00:00:00Z"));
    }

    @Test
    void candidateReviewConsolidationAndScopedRecallWorkTogether() {
        MemoryCandidateView candidate = memoryApi.propose(new ProposeMemoryCandidateCommand(
                MemoryScopeRef.user("user-1"),
                MemoryKind.PREFERENCE,
                "conversation-1",
                "CONVERSATION",
                "The user prefers concise answers",
                0.9,
                "user-preference"));

        memoryApi.review(new ReviewMemoryCandidateCommand(
                candidate.id(), MemoryCandidateState.ACCEPTED, "useful"));

        assertEquals(MemoryCandidateState.ACCEPTED, memoryApi.candidate(candidate.id()).orElseThrow().state());
        assertEquals(0, memoryApi.pending(MemoryScopeRef.user("user-1")).size());
    }

    @Test
    void taskCompletionRetainsUsefulMemoryDiscardsNoiseAndPromotesReusableMemory() {
        MemoryScopeRef taskScope = MemoryScopeRef.task("task-1");
        memoryApi.propose(new ProposeMemoryCandidateCommand(
                taskScope, MemoryKind.ARCHITECTURE, "run-1", "CONVERSATION",
                "Service decomposition follows module boundaries", 0.9, "arch"));
        memoryApi.propose(new ProposeMemoryCandidateCommand(
                taskScope, MemoryKind.PREFERENCE, "run-1", "CONVERSATION",
                "User prefers short diffs", 0.8, "preference"));
        memoryApi.propose(new ProposeMemoryCandidateCommand(
                taskScope, MemoryKind.FACT, "run-1", "CONVERSATION",
                "ok", 0.9, "noise"));

        TaskMemoryConsolidationView result = memoryApi.consolidateTask(
                CompleteTaskMemoryConsolidationCommand.defaults(
                        "task-1", "project-1", "user-1"));

        assertEquals(2, result.accepted());
        assertEquals(1, result.rejected());
        assertEquals(2, result.consolidatedTaskMemories());
        assertEquals(1, result.promotedToProject());
        assertEquals(1, result.promotedToUser());

        assertEquals(2, memoryApi.recall(new MemoryRecallCommand(
                MemoryScopeRef.task("task-1"), List.of(), 20)).size());
        assertEquals(1, memoryApi.recall(new MemoryRecallCommand(
                MemoryScopeRef.project("project-1"), List.of(), 20)).size());
        assertEquals(1, memoryApi.recall(new MemoryRecallCommand(
                MemoryScopeRef.user("user-1"), List.of(), 20)).size());
        assertTrue(memoryApi.recall(new MemoryRecallCommand(
                MemoryScopeRef.task("task-1"), List.of(), 20)).stream()
                .noneMatch(memory -> "ok".equals(memory.value())));
    }
}
