package com.spaceagent.platform.domain;

import com.spaceagent.platform.context.domain.ContextPackage;
import com.spaceagent.platform.context.domain.ContextSource;
import com.spaceagent.platform.context.domain.ContextSourceType;
import com.spaceagent.platform.conversation.domain.ConversationContextSnapshot;
import com.spaceagent.platform.memory.domain.MemoryKind;
import com.spaceagent.platform.memory.domain.MemoryScope;
import com.spaceagent.platform.memory.domain.MemoryScopeRef;
import com.spaceagent.platform.project.domain.SourceRepository;
import com.spaceagent.platform.project.domain.SourceRepositoryState;
import com.spaceagent.platform.project.domain.SourceRepositoryType;
import com.spaceagent.platform.project.domain.Task;
import com.spaceagent.platform.project.domain.TaskState;
import com.spaceagent.platform.runtime.domain.HandoffSnapshot;
import com.spaceagent.platform.runtime.domain.HandoffTestStatus;
import com.spaceagent.platform.tooling.domain.ToolExecutionLedger;
import com.spaceagent.platform.tooling.domain.ToolExecutionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Focused M3 domain contract tests. These tests do not touch Spring or any
 * persistence implementation; they verify only the framework-independent value
 * semantics of the new records, enums, and value objects.
 */
class PlatformDomainContractsTest {

    @Test
    void taskStateDeclaresExpectedTerminalStates() {
        assertTrue(List.of(TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED)
                .stream().allMatch(state -> List.of(TaskState.values()).contains(state)));
    }

    @Test
    void taskIntentListsAreDefensivelyCopied() {
        List<String> constraints = new ArrayList<>(List.of("no external writes"));
        List<String> acceptanceCriteria = new ArrayList<>(List.of("tests pass"));

        Task task = Task.restore(
                "task-1",
                "project-1",
                null,
                "Fix build",
                "Restore the build after the refactor",
                "Inspect, implement, compile, test, and validate architecture.",
                constraints,
                acceptanceCriteria,
                TaskState.READY,
                Instant.EPOCH,
                Instant.EPOCH);

        constraints.add("mutate-after-construction");
        acceptanceCriteria.add("mutate-after-construction");

        assertEquals(List.of("no external writes"), task.constraints());
        assertEquals(List.of("tests pass"), task.acceptanceCriteria());
        assertThrows(UnsupportedOperationException.class,
                () -> task.constraints().add("not allowed"));
    }

    @Test
    void contextPackageDefensivelyCopiesSources() {
        List<ContextSource> mutable = new ArrayList<>();
        mutable.add(new ContextSource(
                ContextSourceType.CONVERSATION,
                "conversation-1",
                "compacted conversation text",
                120,
                10,
                0.91));

        ContextPackage contextPackage = new ContextPackage(
                "ctx-1",
                "user-1",
                "project-1",
                "task-1",
                "conversation-1",
                "agent-run-1",
                4096,
                120,
                mutable,
                "context-compiler",
                Instant.EPOCH);

        mutable.add(new ContextSource(
                ContextSourceType.MEMORY,
                "memory-1",
                "remembered preference",
                10,
                5,
                0.72));

        assertEquals(1, contextPackage.sources().size());
        assertEquals("user-1", contextPackage.userId());
        assertEquals("conversation-1", contextPackage.conversationId());
        assertEquals("agent-run-1", contextPackage.agentRunId());
        assertThrows(UnsupportedOperationException.class,
                () -> contextPackage.sources().add(
                        new ContextSource(
                                ContextSourceType.TOOL,
                                "tool-1",
                                "tool output",
                                5,
                                1,
                                0.5)));
    }

    @Test
    void contextSourceCarriesModelConsumableContentAndMetadata() {
        ContextSource source = new ContextSource(
                ContextSourceType.REPOSITORY,
                "repo-1",
                "source file content",
                256,
                8,
                0.88);

        assertEquals("source file content", source.content());
        assertEquals(256, source.tokenCost());
        assertEquals(8, source.priority());
        assertEquals(0.88, source.relevanceScore());
    }

    @Test
    void contextSourceTypeCoversRequiredOwnershipSources() {
        List<ContextSourceType> values = List.of(ContextSourceType.values());
        List<ContextSourceType> required = List.of(
                ContextSourceType.USER,
                ContextSourceType.PROJECT,
                ContextSourceType.TASK,
                ContextSourceType.REPOSITORY,
                ContextSourceType.CONVERSATION,
                ContextSourceType.MEMORY,
                ContextSourceType.KNOWLEDGE,
                ContextSourceType.TOOL,
                ContextSourceType.SYSTEM,
                ContextSourceType.AGENT_DEFINITION);

        assertTrue(values.containsAll(required));
    }

    @Test
    void conversationSnapshotCarriesVersionSummaryAndRangeMetadata() {
        ConversationContextSnapshot snapshot = new ConversationContextSnapshot(
                "snapshot-1",
                "conversation-1",
                2,
                "compacted conversational context",
                1,
                12,
                900,
                "sha256:abc",
                Instant.EPOCH);

        assertEquals(2, snapshot.version());
        assertEquals("compacted conversational context", snapshot.summary());
        assertEquals(1, snapshot.fromMessageSequence());
        assertEquals(12, snapshot.toMessageSequence());
        assertEquals(900, snapshot.tokenCount());
        assertEquals("sha256:abc", snapshot.checksum());
    }

    @Test
    void memoryScopeRefRemovesAmbiguousOwnerAndScopeIdDuplication() {
        MemoryScopeRef userScope = MemoryScopeRef.user("user-1");
        MemoryScopeRef projectScope = MemoryScopeRef.project("project-1");
        MemoryScopeRef taskScope = MemoryScopeRef.task("task-1");

        assertEquals(new MemoryScopeRef(MemoryScope.USER, "user-1"), userScope);
        assertEquals(new MemoryScopeRef(MemoryScope.PROJECT, "project-1"), projectScope);
        assertEquals(new MemoryScopeRef(MemoryScope.TASK, "task-1"), taskScope);
        assertThrows(IllegalArgumentException.class, () -> new MemoryScopeRef(MemoryScope.USER, " "));
    }

    @Test
    void memoryKindSupportsRequiredMemoryCategories() {
        List<MemoryKind> required = List.of(
                MemoryKind.FACT,
                MemoryKind.PREFERENCE,
                MemoryKind.CONSTRAINT,
                MemoryKind.DECISION,
                MemoryKind.ARCHITECTURE,
                MemoryKind.PROCEDURE,
                MemoryKind.FAILURE,
                MemoryKind.EXPERIENCE);

        assertTrue(List.of(MemoryKind.values()).containsAll(required));
    }

    @Test
    void toolLedgerCarriesDeterministicIdempotencyFields() {
        ToolExecutionLedger ledger = new ToolExecutionLedger(
                "ledger-1",
                "agent-run-1",
                "run-step-1",
                "write-file",
                "tool-call-1",
                "idem-tool-call-1",
                "{\"path\":\"README.md\"}",
                "sha256:arguments",
                ToolExecutionStatus.SUCCEEDED,
                "{\"ok\":true}",
                "artifact://results/agent-run-1/tool-call-1",
                null,
                Instant.EPOCH,
                Instant.EPOCH,
                null,
                null,
                null,
                1L,
                null,
                Instant.EPOCH,
                null,
                null,
                null,
                null);

        assertEquals("sha256:arguments", ledger.inputHash());
        assertEquals("artifact://results/agent-run-1/tool-call-1", ledger.resultRef());
        assertEquals(1L, ledger.revision());
    }

    @Test
    void handoffSnapshotDefensivelyCopiesAndRequiresTestStatus() {
        List<String> completedWork = new ArrayList<>(List.of("fixed build"));
        List<String> decisions = new ArrayList<>(List.of("use store suffix"));
        List<String> failedAttempts = new ArrayList<>(List.of("initial build failure"));
        List<String> changedFiles = new ArrayList<>(List.of("Task.java"));
        List<String> blockers = new ArrayList<>(List.of("none"));
        List<String> nextActions = new ArrayList<>(List.of("run full test suite"));

        HandoffSnapshot snapshot = new HandoffSnapshot(
                "Complete M3 corrections",
                "domain contracts corrected",
                completedWork,
                decisions,
                failedAttempts,
                changedFiles,
                HandoffTestStatus.PARTIAL,
                blockers,
                nextActions);

        completedWork.add("mutate-after-construction");
        changedFiles.add("mutate-after-construction");

        assertEquals(List.of("fixed build"), snapshot.completedWork());
        assertEquals(List.of("Task.java"), snapshot.changedFiles());
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.nextActions().add("not allowed"));
        assertThrows(NullPointerException.class,
                () -> new HandoffSnapshot(
                        "goal",
                        "state",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        List.of()));
    }

    @Test
    void sourceRepositoryIsAnExplicitProjectOwnedDomainConcept() {
        SourceRepository sourceRepository = new SourceRepository(
                "source-repo-1",
                "project-1",
                "https://example.com/team/repo.git",
                "/workspaces/project-1/repo",
                "main",
                SourceRepositoryType.GIT,
                SourceRepositoryState.READY,
                Instant.EPOCH,
                Instant.EPOCH);

        assertEquals("project-1", sourceRepository.projectId());
        assertEquals("main", sourceRepository.defaultBranch());
        assertEquals(SourceRepositoryType.GIT, sourceRepository.type());
        assertEquals(SourceRepositoryState.READY, sourceRepository.state());
    }
}
