package com.spaceagent.platform.runtime;

import com.spaceagent.platform.runtime.domain.ProjectPlanExecutionControl;
import com.spaceagent.platform.runtime.domain.ProjectPlanExecutionControlException;
import com.spaceagent.platform.runtime.domain.ProjectPlanExecutionControlFailure;
import com.spaceagent.platform.runtime.domain.ProjectPlanExecutionDesiredState;
import com.spaceagent.platform.runtime.domain.ProjectPlanExecutionState;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectPlanExecutionControlDomainTest {
    @Test
    void pauseRequiresSafeBoundaryAcknowledgementAndResumeIsExplicit() {
        ProjectPlanExecutionControl ready = ProjectPlanExecutionControl.initial(
                ProjectPlanExecutionState.READY, 4);

        ProjectPlanExecutionControl pausing = ready.requestPause("maintenance");
        assertThat(pausing.state()).isEqualTo(ProjectPlanExecutionState.PAUSING);
        assertThat(pausing.desiredState()).isEqualTo(ProjectPlanExecutionDesiredState.PAUSED);
        assertThat(pausing.revision()).isEqualTo(5);

        ProjectPlanExecutionControl paused = pausing.acknowledgePause();
        assertThat(paused.state()).isEqualTo(ProjectPlanExecutionState.PAUSED);
        assertThat(paused.revision()).isEqualTo(6);
        assertThat(paused.requestPause(null)).isSameAs(paused);

        ProjectPlanExecutionControl resumed = paused.resume();
        assertThat(resumed.state()).isEqualTo(ProjectPlanExecutionState.RUNNING);
        assertThat(resumed.desiredState()).isEqualTo(ProjectPlanExecutionDesiredState.RUNNING);
        assertThat(resumed.revision()).isEqualTo(7);
    }

    @Test
    void cancellationIsIdempotentButCompletesOnlyAtSafeBoundary() {
        ProjectPlanExecutionControl running = ProjectPlanExecutionControl.initial(
                ProjectPlanExecutionState.RUNNING, 1);

        ProjectPlanExecutionControl cancelling = running.requestCancel("user-request");
        assertThat(cancelling.state()).isEqualTo(ProjectPlanExecutionState.CANCELLING);
        assertThat(cancelling.desiredState()).isEqualTo(ProjectPlanExecutionDesiredState.CANCELLED);

        ProjectPlanExecutionControl cancelled = cancelling.acknowledgeCancel();
        assertThat(cancelled.state()).isEqualTo(ProjectPlanExecutionState.CANCELLED);
        assertThat(cancelled.requestCancel(null)).isSameAs(cancelled);
    }

    @Test
    void unknownAmbiguousAndLeaseLossFailClosedWithoutProgression() {
        ProjectPlanExecutionControl running = ProjectPlanExecutionControl.initial(
                ProjectPlanExecutionState.RUNNING, 8);

        ProjectPlanExecutionControl blocked = running.failClosed(
                ProjectPlanExecutionControlFailure.UNKNOWN_EFFECT);
        assertThat(blocked.state()).isEqualTo(ProjectPlanExecutionState.BLOCKED);
        assertThat(blocked.revision()).isEqualTo(9);
        assertThat(blocked.reason()).isEqualTo("PROJECT_PLAN_EXECUTION_UNKNOWN_EFFECT");
        assertThat(blocked.failClosed(ProjectPlanExecutionControlFailure.UNKNOWN_EFFECT))
                .isSameAs(blocked);

        assertThatThrownBy(blocked::resume)
                .isInstanceOf(ProjectPlanExecutionControlException.class)
                .extracting("failure")
                .isEqualTo(ProjectPlanExecutionControlFailure.RECONCILIATION_REQUIRED);
    }

    @Test
    void invalidAndTerminalTransitionsAreRejectedWithoutMutation() {
        ProjectPlanExecutionControl ready = ProjectPlanExecutionControl.initial(
                ProjectPlanExecutionState.READY, 2);

        assertThatThrownBy(ready::acknowledgePause)
                .isInstanceOf(ProjectPlanExecutionControlException.class)
                .extracting("failure")
                .isEqualTo(ProjectPlanExecutionControlFailure.INVALID_TRANSITION);
        assertThatThrownBy(() -> ready.requestPause(""))
                .isInstanceOf(ProjectPlanExecutionControlException.class)
                .extracting("failure")
                .isEqualTo(ProjectPlanExecutionControlFailure.REASON_REQUIRED);
        assertThatThrownBy(() -> ready.requestPause("停".repeat(81)))
                .isInstanceOf(ProjectPlanExecutionControlException.class)
                .extracting("failure")
                .isEqualTo(ProjectPlanExecutionControlFailure.REASON_REQUIRED);

        ProjectPlanExecutionControl completed = ProjectPlanExecutionControl.initial(
                ProjectPlanExecutionState.COMPLETED, 10);
        assertThatThrownBy(() -> completed.failClosed(
                ProjectPlanExecutionControlFailure.AMBIGUOUS_EFFECT))
                .isInstanceOf(ProjectPlanExecutionControlException.class)
                .extracting("failure")
                .isEqualTo(ProjectPlanExecutionControlFailure.TERMINAL_STATE);
    }
}
