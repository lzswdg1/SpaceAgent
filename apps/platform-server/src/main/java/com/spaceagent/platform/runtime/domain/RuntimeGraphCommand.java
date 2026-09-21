package com.spaceagent.platform.runtime.domain;

import com.spaceagent.platform.runtime.domain.multiagent.GraphV2Contract;
import java.time.Instant;
import java.util.Objects;

public record RuntimeGraphCommand(
        String graphSessionId,
        String commandId,
        String inputHash,
        GraphV2Contract.Kind kind,
        long nextCursorSequence,
        String payloadJson,
        State state,
        int attempt,
        String executionOwner,
        String executionToken,
        Long executionFencingToken,
        String safeErrorCode,
        long revision,
        Instant createdAt,
        Instant updatedAt) {

    public enum State { PENDING, EXECUTING, CONFIRMED, BLOCKED, UNKNOWN }

    public RuntimeGraphCommand {
        if (graphSessionId == null || graphSessionId.isBlank()
                || commandId == null || commandId.isBlank()
                || inputHash == null || !inputHash.matches("sha256:[0-9a-f]{64}")
                || kind == null || nextCursorSequence < 1 || payloadJson == null
                || attempt < 0 || revision <= 0 || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("graph command");
        }
        boolean executionEvidence = executionOwner != null || executionToken != null
                || executionFencingToken != null;
        if ((state == State.EXECUTING) != executionEvidence
                || executionEvidence && (executionOwner.isBlank() || executionToken.isBlank()
                || executionFencingToken < 1)
                || (state == State.BLOCKED || state == State.UNKNOWN) != (safeErrorCode != null)) {
            throw new IllegalArgumentException("graph command execution evidence");
        }
        Objects.requireNonNull(state);
    }

    public RuntimeGraphCommand(
            String graphSessionId, String commandId, String inputHash, State state,
            long revision, Instant createdAt, Instant updatedAt) {
        this(graphSessionId, commandId, inputHash, GraphV2Contract.Kind.WAIT_FOR_APPROVAL,
                1, "{}", state, 0, null, null, null,
                state == State.BLOCKED ? "GRAPH_COMMAND_BLOCKED" : null,
                revision, createdAt, updatedAt);
    }

    public RuntimeGraphCommand begin(String owner, String token, long fence, Instant at) {
        if (state != State.PENDING) throw new IllegalStateException("graph command is not pending");
        return copy(State.EXECUTING, attempt + 1, owner, token, fence, null, at);
    }

    public RuntimeGraphCommand confirm(String owner, String token, long fence, Instant at) {
        requireExecution(owner, token, fence);
        return copy(State.CONFIRMED, attempt, null, null, null, null, at);
    }

    public RuntimeGraphCommand confirmWithoutEffect(Instant at) {
        if (state != State.PENDING) throw new IllegalStateException("graph command is not pending");
        return copy(State.CONFIRMED, attempt, null, null, null, null, at);
    }

    public RuntimeGraphCommand blockUnknown(Instant at) {
        if (state == State.UNKNOWN) return this;
        if (state != State.PENDING && state != State.EXECUTING) {
            throw new IllegalStateException("graph command cannot become unknown");
        }
        return copy(State.UNKNOWN, attempt, null, null, null,
                "GRAPH_COMMAND_OUTCOME_UNKNOWN", at);
    }

    public RuntimeGraphCommand blockKnown(String safeCode, Instant at) {
        if (state != State.PENDING && state != State.EXECUTING) {
            throw new IllegalStateException("graph command cannot block");
        }
        return copy(State.BLOCKED, attempt, null, null, null, safeCode, at);
    }

    public void requireExecution(String owner, String token, long fence) {
        if (state != State.EXECUTING || !Objects.equals(executionOwner, owner)
                || !Objects.equals(executionToken, token)
                || !Objects.equals(executionFencingToken, fence)) {
            throw new IllegalStateException("graph command execution fence mismatch");
        }
    }

    private RuntimeGraphCommand copy(
            State next, int nextAttempt, String owner, String token, Long fence,
            String error, Instant at) {
        return new RuntimeGraphCommand(graphSessionId, commandId, inputHash, kind,
                nextCursorSequence, payloadJson, next, nextAttempt, owner, token, fence,
                error, revision + 1, createdAt, at);
    }
}
