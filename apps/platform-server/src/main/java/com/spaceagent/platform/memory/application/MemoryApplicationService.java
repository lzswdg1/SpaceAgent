package com.spaceagent.platform.memory.application;

import com.spaceagent.platform.memory.api.CompleteTaskMemoryConsolidationCommand;
import com.spaceagent.platform.memory.api.EvaluateMessageMemoryCommand;
import com.spaceagent.platform.memory.api.MemoryApplicationApi;
import com.spaceagent.platform.memory.api.MemoryCandidateView;
import com.spaceagent.platform.memory.api.MemoryEvaluationView;
import com.spaceagent.platform.memory.api.MemoryOwnershipPort;
import com.spaceagent.platform.memory.api.MemoryRecallCommand;
import com.spaceagent.platform.memory.api.ProposeMemoryCandidateCommand;
import com.spaceagent.platform.memory.api.ReviewMemoryCandidateCommand;
import com.spaceagent.platform.memory.api.ScopedMemoryView;
import com.spaceagent.platform.memory.api.TaskMemoryConsolidationView;
import com.spaceagent.platform.memory.api.SaveProjectMemorySnapshotCommand;
import com.spaceagent.platform.memory.domain.ConsolidatedMemory;
import com.spaceagent.platform.memory.domain.ConsolidatedMemoryRepository;
import com.spaceagent.platform.memory.domain.MemoryCandidate;
import com.spaceagent.platform.memory.domain.MemoryCandidateRepository;
import com.spaceagent.platform.memory.domain.MemoryCandidateState;
import com.spaceagent.platform.memory.domain.MemoryKind;
import com.spaceagent.platform.memory.domain.MemoryScope;
import com.spaceagent.platform.memory.domain.MemoryScopeRef;
import com.spaceagent.platform.memory.domain.ProjectMemory;
import com.spaceagent.platform.memory.domain.TaskMemory;
import com.spaceagent.platform.memory.domain.UserMemory;
import com.spaceagent.shared.exception.BusinessException;
import com.spaceagent.shared.id.IdGenerator;
import com.spaceagent.shared.time.TimeProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Memory lifecycle coordinator. It owns candidate review, consolidation, scoped recall,
 * and task-completion promotion/discard rules.
 *
 * <p>This service never writes every conversational message as memory. Callers submit
 * candidates, which are reviewed and consolidated through explicit rules.
 */
@Service
public class MemoryApplicationService implements MemoryApplicationApi, MemoryOwnershipPort {

    private static final int MINIMUM_CONTENT_LENGTH = 8;
    private static final Set<String> NOISE_PREFIXES = Set.of(
            "你好", "谢谢", "ok", "好的", "收到", "明白", "哈哈", "嗯", "哦");

    private final MemoryCandidateRepository candidateRepository;
    private final ConsolidatedMemoryRepository memoryRepository;
    private final IdGenerator idGenerator;
    private final TimeProvider timeProvider;

    public MemoryApplicationService(
            MemoryCandidateRepository candidateRepository,
            ConsolidatedMemoryRepository memoryRepository,
            IdGenerator idGenerator,
            TimeProvider timeProvider) {
        this.candidateRepository = candidateRepository;
        this.memoryRepository = memoryRepository;
        this.idGenerator = idGenerator;
        this.timeProvider = timeProvider;
    }

    @Override
    public MemoryCandidateView propose(ProposeMemoryCandidateCommand command) {
        Instant now = timeProvider.now();
        MemoryCandidate candidate = new MemoryCandidate(
                idGenerator.nextId(),
                command.scope(),
                command.kind(),
                command.sourceId(),
                command.sourceType(),
                command.content(),
                command.confidence(),
                command.dedupeKey(),
                MemoryCandidateState.PENDING,
                now);
        candidateRepository.save(candidate);
        return toCandidateView(candidate);
    }

    @Override
    public MemoryCandidateView review(ReviewMemoryCandidateCommand command) {
        MemoryCandidate candidate = requireCandidate(command.candidateId());
        if (candidate.state() == MemoryCandidateState.CONSOLIDATED) {
            throw new BusinessException(
                    "Candidate is already consolidated",
                    HttpStatus.CONFLICT);
        }
        if (candidate.state() == command.decision()) {
            return toCandidateView(candidate);
        }
        MemoryCandidate updated = new MemoryCandidate(
                candidate.id(),
                candidate.scope(),
                candidate.kind(),
                candidate.sourceId(),
                candidate.sourceType(),
                candidate.content(),
                candidate.confidence(),
                candidate.dedupeKey(),
                command.decision(),
                candidate.createdAt());
        candidateRepository.save(updated);
        return toCandidateView(updated);
    }

    @Override
    public MemoryEvaluationView evaluateMessage(EvaluateMessageMemoryCommand command) {
        String content = command.userMessage().trim();
        if (!isDurableCandidate(content)) {
            return MemoryEvaluationView.skipped();
        }
        MemoryKind kind = classify(content);
        MemoryCandidateView candidate = propose(new ProposeMemoryCandidateCommand(
                MemoryScopeRef.user(command.userId()),
                kind,
                command.conversationId(),
                "CONVERSATION_MESSAGE_EVALUATION",
                content,
                0.85,
                kind.name() + ":" + content.toLowerCase(Locale.ROOT)));
        return new MemoryEvaluationView(true, candidate);
    }

    @Override
    public List<MemoryCandidateView> pending(MemoryScopeRef scope) {
        return candidateRepository.findByScopeAndState(scope, MemoryCandidateState.PENDING).stream()
                .map(MemoryApplicationService::toCandidateView)
                .toList();
    }

    @Override
    public Optional<MemoryCandidateView> candidate(String candidateId) {
        return candidateRepository.findById(candidateId)
                .map(MemoryApplicationService::toCandidateView);
    }

    @Override
    public List<ScopedMemoryView> recall(MemoryRecallCommand command) {
        return memoryRepository.findRecentByScope(
                        command.scope(), command.kinds(), command.limit()).stream()
                .map(MemoryApplicationService::toMemoryView)
                .toList();
    }

    @Override
    public TaskMemoryConsolidationView consolidateTask(
            CompleteTaskMemoryConsolidationCommand command) {
        MemoryScopeRef taskScope = MemoryScopeRef.task(command.taskId());
        List<MemoryCandidate> candidates = candidateRepository.findByScope(taskScope);

        int accepted = 0;
        int rejected = 0;
        int promotedToProject = 0;
        int promotedToUser = 0;
        List<ScopedMemoryView> consolidated = new ArrayList<>();

        for (MemoryCandidate candidate : candidates) {
            if (candidate.state() == MemoryCandidateState.CONSOLIDATED) {
                continue;
            }
            if (shouldDiscard(candidate, command.acceptThreshold())) {
                saveCandidateState(candidate, MemoryCandidateState.REJECTED);
                rejected++;
                continue;
            }

            saveCandidateState(candidate, MemoryCandidateState.ACCEPTED);
            accepted++;

            TaskMemory taskMemory = new TaskMemory(
                    idGenerator.nextId(),
                    candidate.scope().scopeId(),
                    candidate.kind(),
                    memoryKey(candidate),
                    candidate.content(),
                    timeProvider.now(),
                    timeProvider.now());
            saveMemory(toConsolidated(taskMemory));
            consolidated.add(toMemoryView(toConsolidated(taskMemory)));

            if (shouldPromote(candidate, command.promotionThreshold())) {
                if (isProjectReusable(candidate.kind())
                        && command.projectId() != null && !command.projectId().isBlank()) {
                    ProjectMemory projectMemory = new ProjectMemory(
                            idGenerator.nextId(),
                            command.projectId(),
                            candidate.kind(),
                            memoryKey(candidate),
                            candidate.content(),
                            timeProvider.now(),
                            timeProvider.now());
                    saveMemory(toConsolidated(projectMemory));
                    promotedToProject++;
                }
                if (isUserReusable(candidate.kind())
                        && command.userId() != null && !command.userId().isBlank()) {
                    UserMemory userMemory = new UserMemory(
                            idGenerator.nextId(),
                            command.userId(),
                            candidate.kind(),
                            memoryKey(candidate),
                            candidate.content(),
                            timeProvider.now(),
                            timeProvider.now());
                    saveMemory(toConsolidated(userMemory));
                    promotedToUser++;
                }
            }

            saveCandidateState(candidate, MemoryCandidateState.CONSOLIDATED);
        }

        return new TaskMemoryConsolidationView(
                command.taskId(),
                accepted,
                rejected,
                consolidated.size(),
                promotedToProject,
                promotedToUser,
                consolidated);
    }

    @Override
    public ScopedMemoryView saveProjectSnapshot(SaveProjectMemorySnapshotCommand command) {
        if (command.projectId() == null || command.projectId().isBlank()
                || command.key() == null || command.key().isBlank()
                || command.value() == null || command.value().isBlank()
                || command.kind() == null) {
            throw new IllegalArgumentException("Project memory snapshot fields are required");
        }
        Instant now = timeProvider.now();
        ConsolidatedMemory memory = new ConsolidatedMemory(
                idGenerator.nextId(), MemoryScopeRef.project(command.projectId()),
                command.kind(), command.key().trim(), command.value().trim(), now, now);
        saveMemory(memory);
        return toMemoryView(memory);
    }

    @Override
    public boolean isOwner(String memoryId, String principalId) {
        return memoryRepository.findById(memoryId)
                .map(memory -> principalId != null && principalId.equals(memory.scope().scopeId()))
                .orElse(false);
    }

    @Override
    public boolean canAccessScope(MemoryScopeRef scope, String principalId) {
        return principalId != null && principalId.equals(scope.scopeId());
    }

    private boolean shouldDiscard(MemoryCandidate candidate, double acceptThreshold) {
        if (candidate.confidence() < acceptThreshold) {
            return true;
        }
        String content = candidate.content() == null ? "" : candidate.content().trim();
        if (content.length() < MINIMUM_CONTENT_LENGTH) {
            return true;
        }
        String normalized = content.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}]+", "");
        return NOISE_PREFIXES.stream().anyMatch(prefix ->
                normalized.startsWith(prefix.toLowerCase(Locale.ROOT)));
    }

    private boolean isDurableCandidate(String content) {
        if (content.length() < 16) {
            return false;
        }
        String normalized = content.toLowerCase(Locale.ROOT);
        return List.of(
                        "remember", "my preference", "i prefer", "i always", "we decided",
                        "记住", "我的偏好", "我喜欢", "以后", "我们决定", "架构约束")
                .stream()
                .anyMatch(normalized::contains);
    }

    private MemoryKind classify(String content) {
        String normalized = content.toLowerCase(Locale.ROOT);
        if (normalized.contains("prefer") || normalized.contains("偏好") || normalized.contains("喜欢")) {
            return MemoryKind.PREFERENCE;
        }
        if (normalized.contains("decided") || normalized.contains("决定")) {
            return MemoryKind.DECISION;
        }
        if (normalized.contains("architecture") || normalized.contains("架构")) {
            return MemoryKind.ARCHITECTURE;
        }
        return MemoryKind.FACT;
    }

    private boolean shouldPromote(MemoryCandidate candidate, double promotionThreshold) {
        return candidate.confidence() >= promotionThreshold
                && (isProjectReusable(candidate.kind()) || isUserReusable(candidate.kind()));
    }

    private boolean isProjectReusable(MemoryKind kind) {
        return kind == MemoryKind.ARCHITECTURE
                || kind == MemoryKind.PROCEDURE
                || kind == MemoryKind.DECISION
                || kind == MemoryKind.CONSTRAINT;
    }

    private boolean isUserReusable(MemoryKind kind) {
        return kind == MemoryKind.PREFERENCE
                || kind == MemoryKind.FACT
                || kind == MemoryKind.EXPERIENCE;
    }

    private String memoryKey(MemoryCandidate candidate) {
        String key = candidate.dedupeKey();
        if (key == null || key.isBlank()) {
            key = candidate.kind().name() + ":" + candidate.content();
        }
        return key.length() <= 240 ? key : key.substring(0, 240);
    }

    private ConsolidatedMemory toConsolidated(TaskMemory memory) {
        return new ConsolidatedMemory(
                memory.id(),
                memory.scope(),
                memory.kind(),
                memory.key(),
                memory.value(),
                memory.createdAt(),
                memory.updatedAt());
    }

    private ConsolidatedMemory toConsolidated(ProjectMemory memory) {
        return new ConsolidatedMemory(
                memory.id(),
                memory.scope(),
                memory.kind(),
                memory.key(),
                memory.value(),
                memory.createdAt(),
                memory.updatedAt());
    }

    private ConsolidatedMemory toConsolidated(UserMemory memory) {
        return new ConsolidatedMemory(
                memory.id(),
                memory.scope(),
                memory.kind(),
                memory.key(),
                memory.value(),
                memory.createdAt(),
                memory.updatedAt());
    }

    private void saveMemory(ConsolidatedMemory memory) {
        memoryRepository.save(memory);
    }

    private MemoryCandidate requireCandidate(String candidateId) {
        return candidateRepository.findById(candidateId)
                .orElseThrow(() -> new BusinessException(
                        "Memory candidate not found: " + candidateId,
                        HttpStatus.NOT_FOUND));
    }

    private void saveCandidateState(MemoryCandidate candidate, MemoryCandidateState state) {
        candidateRepository.save(new MemoryCandidate(
                candidate.id(),
                candidate.scope(),
                candidate.kind(),
                candidate.sourceId(),
                candidate.sourceType(),
                candidate.content(),
                candidate.confidence(),
                candidate.dedupeKey(),
                state,
                candidate.createdAt()));
    }

    private static MemoryCandidateView toCandidateView(MemoryCandidate candidate) {
        return new MemoryCandidateView(
                candidate.id(),
                candidate.scope(),
                candidate.kind(),
                candidate.sourceId(),
                candidate.sourceType(),
                candidate.content(),
                candidate.confidence(),
                candidate.dedupeKey(),
                candidate.state(),
                candidate.createdAt());
    }

    private static ScopedMemoryView toMemoryView(ConsolidatedMemory memory) {
        return new ScopedMemoryView(
                memory.id(),
                memory.scope(),
                memory.kind(),
                memory.key(),
                memory.value(),
                memory.createdAt(),
                memory.updatedAt());
    }
}
