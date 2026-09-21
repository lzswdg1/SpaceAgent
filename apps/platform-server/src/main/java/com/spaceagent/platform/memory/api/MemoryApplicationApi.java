package com.spaceagent.platform.memory.api;

import com.spaceagent.platform.memory.domain.MemoryKind;
import com.spaceagent.platform.memory.domain.MemoryScopeRef;

import java.util.List;
import java.util.Optional;

/**
 * Public memory application API. It owns the memory lifecycle from candidate to
 * scoped durable memory; callers do not write memory as a chat side-effect.
 */
public interface MemoryApplicationApi {

    MemoryCandidateView propose(ProposeMemoryCandidateCommand command);

    MemoryCandidateView review(ReviewMemoryCandidateCommand command);

    MemoryEvaluationView evaluateMessage(EvaluateMessageMemoryCommand command);

    List<MemoryCandidateView> pending(MemoryScopeRef scope);

    Optional<MemoryCandidateView> candidate(String candidateId);

    List<ScopedMemoryView> recall(MemoryRecallCommand command);

    TaskMemoryConsolidationView consolidateTask(
            CompleteTaskMemoryConsolidationCommand command);

    ScopedMemoryView saveProjectSnapshot(SaveProjectMemorySnapshotCommand command);
}
