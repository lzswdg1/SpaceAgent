package com.spaceagent.platform.runtime;

import com.spaceagent.platform.runtime.domain.ProjectPlanReadyWave;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProjectPlanReadyWaveTest {
    @Test void selectsStableBoundedWaveOnlyAfterEveryDependencyCompletes() {
        var wave = ProjectPlanReadyWave.select(List.of(
                step("a", 2, List.of(), ProjectPlanReadyWave.StepState.READY),
                step("b", 1, List.of(), ProjectPlanReadyWave.StepState.PENDING),
                step("c", 3, List.of("b"), ProjectPlanReadyWave.StepState.PENDING)), 2);
        assertThat(wave.readyStepIds()).containsExactly("b", "a");
        assertThat(wave.blockedStepIds()).isEmpty();
    }

    @Test void doesNotDispatchDownstreamOfFailedUnknownOrCancelledDependency() {
        var wave = ProjectPlanReadyWave.select(List.of(
                step("failed", 1, List.of(), ProjectPlanReadyWave.StepState.FAILED),
                step("unknown", 2, List.of(), ProjectPlanReadyWave.StepState.BLOCKED),
                step("after-failed", 3, List.of("failed"), ProjectPlanReadyWave.StepState.PENDING),
                step("after-unknown", 4, List.of("unknown"), ProjectPlanReadyWave.StepState.READY)), 4);
        assertThat(wave.readyStepIds()).isEmpty();
        assertThat(wave.blockedStepIds()).containsExactly("after-failed", "after-unknown");
    }

    @Test void rejectsUnknownDependenciesAndInvalidParallelism() {
        assertThatThrownBy(() -> ProjectPlanReadyWave.select(List.of(
                step("a", 0, List.of("missing"), ProjectPlanReadyWave.StepState.PENDING)), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProjectPlanReadyWave.select(List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ProjectPlanReadyWave.Step step(String id, int sequence, List<String> dependencies,
            ProjectPlanReadyWave.StepState state) { return new ProjectPlanReadyWave.Step(id, sequence, dependencies, state); }
}
