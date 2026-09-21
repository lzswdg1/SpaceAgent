package com.spaceagent.platform.context.application;

import com.spaceagent.platform.context.api.CompileContextCommand;
import com.spaceagent.platform.context.api.ContextCompilerApplicationApi;
import com.spaceagent.platform.context.domain.ContextPackage;
import com.spaceagent.platform.context.domain.ContextSource;
import com.spaceagent.platform.context.domain.ContextSourceType;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Default application-level context compiler.
 *
 * <p>It does not blindly concatenate inputs. It applies token budgeting, priority,
 * relevance, and type-aware deduplication before producing a ContextPackage.
 */
@Service
public class ContextCompilerService implements ContextCompilerApplicationApi {

    private static final int SOURCE_OVERHEAD_TOKENS = 4;
    private static final int ASCII_WORD_CHARS_PER_TOKEN = 4;

    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;

    public ContextCompilerService(IdGenerator idGenerator, TimeProvider timeProvider) {
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
    }

    @Override
    public ContextPackage compile(CompileContextCommand command) {
        List<ContextSource> selected = new ArrayList<>();
        int remaining = command.tokenBudget();
        Map<String, ContextSource> deduped = dedupe(command.contributions());

        List<ContextSource> ordered = deduped.values().stream()
                .sorted(priorityThenRelevance())
                .toList();

        for (ContextSource source : ordered) {
            int cost = source.tokenCost();
            if (cost > remaining) {
                continue;
            }
            selected.add(source);
            remaining -= cost;
        }

        int usedTokens = command.tokenBudget() - remaining;
        return new ContextPackage(
                idGenerator.nextId(),
                command.userId(),
                normalizeOptional(command.projectId()),
                normalizeOptional(command.taskId()),
                normalizeOptional(command.conversationId()),
                normalizeOptional(command.agentRunId()),
                command.tokenBudget(),
                usedTokens,
                selected,
                command.compiledBy(),
                timeProvider.now());
    }

    private Map<String, ContextSource> dedupe(
            List<CompileContextCommand.ContextContribution> contributions) {
        Map<String, ContextSource> result = new LinkedHashMap<>();
        for (CompileContextCommand.ContextContribution contribution : contributions) {
            ContextSource source = toSource(contribution);
            String key = source.type().name() + "|" + contribution.dedupeKey();
            ContextSource existing = result.get(key);
            if (existing == null || keepNew(existing, source)) {
                result.put(key, source);
            }
        }
        return result;
    }

    private boolean keepNew(ContextSource existing, ContextSource candidate) {
        if (candidate.priority() != existing.priority()) {
            return candidate.priority() > existing.priority();
        }
        return candidate.relevanceScore() > existing.relevanceScore();
    }

    private ContextSource toSource(CompileContextCommand.ContextContribution contribution) {
        int contentTokens = estimateTokens(contribution.content());
        return new ContextSource(
                contribution.type(),
                contribution.sourceId(),
                contribution.content(),
                contentTokens + SOURCE_OVERHEAD_TOKENS,
                contribution.priority(),
                contribution.relevanceScore());
    }

    private Comparator<ContextSource> priorityThenRelevance() {
        return Comparator
                .comparingInt(ContextSource::priority)
                .thenComparingDouble(ContextSource::relevanceScore)
                .reversed();
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Conservative deterministic token estimate. This lives in the application layer,
     * not the domain, because it is an implementation detail of compilation.
     */
    static int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int tokens = 0;
        int asciiWordLength = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (isCjk(codePoint)) {
                tokens += flushAsciiWord(asciiWordLength) + 1;
                asciiWordLength = 0;
            } else if (isAsciiWord(codePoint)) {
                asciiWordLength++;
            } else if (Character.isWhitespace(codePoint)) {
                tokens += flushAsciiWord(asciiWordLength);
                asciiWordLength = 0;
            } else {
                tokens += flushAsciiWord(asciiWordLength) + 1;
                asciiWordLength = 0;
            }
        }
        return tokens + flushAsciiWord(asciiWordLength);
    }

    private static int flushAsciiWord(int length) {
        return length == 0 ? 0 : (length + ASCII_WORD_CHARS_PER_TOKEN - 1) / ASCII_WORD_CHARS_PER_TOKEN;
    }

    private static boolean isAsciiWord(int codePoint) {
        return codePoint < 128 && (Character.isLetterOrDigit(codePoint) || codePoint == '_' || codePoint == '-');
    }

    private static boolean isCjk(int codePoint) {
        Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
