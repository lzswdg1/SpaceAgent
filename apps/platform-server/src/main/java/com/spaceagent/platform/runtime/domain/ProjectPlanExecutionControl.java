package com.spaceagent.platform.runtime.domain;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Pure Runtime control state machine for pause, resume and cancellation requests.
 *
 * <p>The request states and safe-boundary acknowledgements are deliberately separate:
 * coordinators may request a control action immediately, but only the Runtime worker at
 * a side-effect-safe boundary may acknowledge PAUSED or CANCELLED.</p>
 */
public record ProjectPlanExecutionControl(
        ProjectPlanExecutionState state,
        ProjectPlanExecutionDesiredState desiredState,
        String reason,
        long revision) {

    public ProjectPlanExecutionControl {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(desiredState, "desiredState");
        if (reason != null) {
            reason = normalizeReason(reason);
        }
        if (revision < 1) {
            throw new IllegalArgumentException("revision is invalid");
        }
    }

    public static ProjectPlanExecutionControl initial(
            ProjectPlanExecutionState state, long revision) {
        Objects.requireNonNull(state, "state");
        ProjectPlanExecutionDesiredState desired = switch (state) {
            case PAUSING, PAUSED -> ProjectPlanExecutionDesiredState.PAUSED;
            case CANCELLING, CANCELLED -> ProjectPlanExecutionDesiredState.CANCELLED;
            default -> ProjectPlanExecutionDesiredState.RUNNING;
        };
        return new ProjectPlanExecutionControl(state, desired, null, revision);
    }

    /** Request a pause. The actual pause is acknowledged separately at a safe boundary. */
    public ProjectPlanExecutionControl requestPause(String pauseReason) {
        if (state == ProjectPlanExecutionState.PAUSING
                || state == ProjectPlanExecutionState.PAUSED) {
            return this;
        }
        requireReason(pauseReason);
        if (state != ProjectPlanExecutionState.READY
                && state != ProjectPlanExecutionState.RUNNING) {
            throw failure(state == ProjectPlanExecutionState.BLOCKED
                    ? ProjectPlanExecutionControlFailure.RECONCILIATION_REQUIRED
                    : terminalOrInvalid(state));
        }
        return next(ProjectPlanExecutionState.PAUSING,
                ProjectPlanExecutionDesiredState.PAUSED, pauseReason);
    }

    /** Acknowledge pause only after the current worker reaches a side-effect-safe boundary. */
    public ProjectPlanExecutionControl acknowledgePause() {
        if (state == ProjectPlanExecutionState.PAUSED) {
            return this;
        }
        if (state != ProjectPlanExecutionState.PAUSING) {
            throw failure(state == ProjectPlanExecutionState.BLOCKED
                    ? ProjectPlanExecutionControlFailure.RECONCILIATION_REQUIRED
                    : terminalOrInvalid(state));
        }
        return next(ProjectPlanExecutionState.PAUSED,
                ProjectPlanExecutionDesiredState.PAUSED, reason);
    }

    /** Resume only from a safely paused execution; blocked/ambiguous work needs reconciliation. */
    public ProjectPlanExecutionControl resume() {
        if (state == ProjectPlanExecutionState.READY
                || state == ProjectPlanExecutionState.RUNNING) {
            return this;
        }
        if (state == ProjectPlanExecutionState.BLOCKED) {
            throw failure(ProjectPlanExecutionControlFailure.RECONCILIATION_REQUIRED);
        }
        if (state != ProjectPlanExecutionState.PAUSED) {
            throw failure(terminalOrInvalid(state));
        }
        return next(ProjectPlanExecutionState.RUNNING,
                ProjectPlanExecutionDesiredState.RUNNING, null);
    }

    /** Request cancellation. The actual cancellation is acknowledged separately at a safe boundary. */
    public ProjectPlanExecutionControl requestCancel(String cancelReason) {
        if (state == ProjectPlanExecutionState.CANCELLING
                || state == ProjectPlanExecutionState.CANCELLED) {
            return this;
        }
        requireReason(cancelReason);
        if (state != ProjectPlanExecutionState.READY
                && state != ProjectPlanExecutionState.RUNNING
                && state != ProjectPlanExecutionState.PAUSING
                && state != ProjectPlanExecutionState.PAUSED) {
            throw failure(state == ProjectPlanExecutionState.BLOCKED
                    ? ProjectPlanExecutionControlFailure.RECONCILIATION_REQUIRED
                    : terminalOrInvalid(state));
        }
        return next(ProjectPlanExecutionState.CANCELLING,
                ProjectPlanExecutionDesiredState.CANCELLED, cancelReason);
    }

    /** Acknowledge cancellation only after pending work has been fenced at a safe boundary. */
    public ProjectPlanExecutionControl acknowledgeCancel() {
        if (state == ProjectPlanExecutionState.CANCELLED) {
            return this;
        }
        if (state != ProjectPlanExecutionState.CANCELLING) {
            throw failure(state == ProjectPlanExecutionState.BLOCKED
                    ? ProjectPlanExecutionControlFailure.RECONCILIATION_REQUIRED
                    : terminalOrInvalid(state));
        }
        return next(ProjectPlanExecutionState.CANCELLED,
                ProjectPlanExecutionDesiredState.CANCELLED, reason);
    }

    /** Fail closed for an effect whose outcome cannot be safely inferred or whose lease was lost. */
    public ProjectPlanExecutionControl failClosed(ProjectPlanExecutionControlFailure failure) {
        Objects.requireNonNull(failure, "failure");
        if (failure != ProjectPlanExecutionControlFailure.UNKNOWN_EFFECT
                && failure != ProjectPlanExecutionControlFailure.AMBIGUOUS_EFFECT
                && failure != ProjectPlanExecutionControlFailure.LEASE_LOST) {
            throw failure(ProjectPlanExecutionControlFailure.INVALID_TRANSITION);
        }
        if (state == ProjectPlanExecutionState.BLOCKED
                && failure.safeErrorCode().equals(reason)) {
            return this;
        }
        if (terminal(state)) {
            throw failure(ProjectPlanExecutionControlFailure.TERMINAL_STATE);
        }
        return next(ProjectPlanExecutionState.BLOCKED, desiredState, failure.safeErrorCode());
    }

    private ProjectPlanExecutionControl next(
            ProjectPlanExecutionState nextState,
            ProjectPlanExecutionDesiredState nextDesired,
            String nextReason) {
        return new ProjectPlanExecutionControl(nextState, nextDesired, nextReason, revision + 1);
    }

    private static ProjectPlanExecutionControlFailure terminalOrInvalid(
            ProjectPlanExecutionState state) {
        return terminal(state)
                ? ProjectPlanExecutionControlFailure.TERMINAL_STATE
                : ProjectPlanExecutionControlFailure.INVALID_TRANSITION;
    }

    private static boolean terminal(ProjectPlanExecutionState state) {
        return state == ProjectPlanExecutionState.COMPLETED
                || state == ProjectPlanExecutionState.FAILED
                || state == ProjectPlanExecutionState.CANCELLED;
    }

    private static void requireReason(String value) {
        if (value == null || value.isBlank()) {
            throw failure(ProjectPlanExecutionControlFailure.REASON_REQUIRED);
        }
        normalizeReason(value);
    }

    private static String normalizeReason(String value) {
        String normalized = value.trim();
        if (normalized.isEmpty()
                || normalized.getBytes(StandardCharsets.UTF_8).length > 240
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw failure(ProjectPlanExecutionControlFailure.REASON_REQUIRED);
        }
        return normalized;
    }

    private static ProjectPlanExecutionControlException failure(
            ProjectPlanExecutionControlFailure failure) {
        return new ProjectPlanExecutionControlException(failure);
    }
}
