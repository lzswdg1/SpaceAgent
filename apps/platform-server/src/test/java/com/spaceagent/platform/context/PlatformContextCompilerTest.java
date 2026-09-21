package com.spaceagent.platform.context;

import com.spaceagent.platform.context.api.CompileContextCommand;
import com.spaceagent.platform.context.api.ContextCompilerApplicationApi;
import com.spaceagent.platform.context.application.ContextCompilerService;
import com.spaceagent.platform.context.domain.ContextPackage;
import com.spaceagent.platform.context.domain.ContextSourceType;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformContextCompilerTest {

    private ContextCompilerApplicationApi compiler;

    @BeforeEach
    void setUp() {
        compiler = new ContextCompilerService(
                () -> "package-1",
                () -> Instant.parse("2026-08-18T00:00:00Z"));
    }

    @Test
    void compileAppliesBudgetPriorityRelevanceAndDeduplication() {
        ContextPackage result = compiler.compile(new CompileContextCommand(
                "user-1",
                null,
                null,
                "conversation-1",
                "run-1",
                40,
                "test",
                List.of(
                        contribution(ContextSourceType.USER, "user-1", "User context", 100, 0.9, "user"),
                        contribution(ContextSourceType.USER, "user-1", "User context", 100, 0.9, "user"),
                        contribution(ContextSourceType.CONVERSATION, "conversation-1", "recent turn", 50, 0.5, "turn"),
                        contribution(ContextSourceType.TOOL, "tool-1", "tool schema", 20, 0.2, "tool")
                )));

        assertEquals(40, result.tokenBudget());
        assertTrue(result.usedTokens() > 0);
        assertEquals(1, result.sources().stream()
                .filter(source -> source.type() == ContextSourceType.USER)
                .count());
        assertEquals("user-1", result.userId());
        assertEquals("conversation-1", result.conversationId());
        assertEquals("run-1", result.agentRunId());
        assertEquals(1, result.sources().stream()
                .filter(source -> source.type() == ContextSourceType.USER)
                .count());
    }

    @Test
    void compileSkipsContentThatDoesNotFitRemainingBudget() {
        ContextPackage result = compiler.compile(new CompileContextCommand(
                "user-1",
                null,
                null,
                "conversation-1",
                "run-1",
                1,
                "test",
                List.of(
                        contribution(ContextSourceType.KNOWLEDGE, "kb-1",
                                "This is deliberately long knowledge context that exceeds budget", 99, 0.9, "kb")
                )));

        assertTrue(result.sources().isEmpty());
        assertEquals(0, result.usedTokens());
    }

    private CompileContextCommand.ContextContribution contribution(
            ContextSourceType type,
            String sourceId,
            String content,
            int priority,
            double relevance,
            String dedupeKey) {
        return new CompileContextCommand.ContextContribution(
                type, sourceId, content, priority, relevance, dedupeKey);
    }
}
