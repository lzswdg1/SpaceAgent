package com.spaceagent.platform.runtime.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Pure deterministic ready-wave decision; persistence and dispatch are deliberately separate. */
public record ProjectPlanReadyWave(List<String> readyStepIds, List<String> blockedStepIds) {
    public ProjectPlanReadyWave {
        readyStepIds = List.copyOf(readyStepIds);
        blockedStepIds = List.copyOf(blockedStepIds);
    }

    public static ProjectPlanReadyWave select(List<Step> steps, int maximumParallelism) {
        if (maximumParallelism < 1) throw new IllegalArgumentException("maximumParallelism must be positive");
        List<Step> all = List.copyOf(Objects.requireNonNull(steps, "steps"));
        Set<String> ids = all.stream().map(Step::id).collect(java.util.stream.Collectors.toSet());
        if (ids.size() != all.size() || all.stream().anyMatch(step -> !ids.containsAll(step.dependencyStepIds()))) {
            throw new IllegalArgumentException("ready wave has invalid step dependencies");
        }
        java.util.Map<String, StepState> state = all.stream().collect(java.util.stream.Collectors.toMap(
                Step::id, Step::state));
        Comparator<Step> stable = Comparator.comparingInt(Step::sequence).thenComparing(Step::id);
        List<String> blocked = all.stream().filter(step -> isWaiting(step.state()))
                .filter(step -> step.dependencyStepIds().stream().map(state::get).anyMatch(ProjectPlanReadyWave::blocks))
                .sorted(stable).map(Step::id).toList();
        List<String> ready = all.stream().filter(step -> isWaiting(step.state()))
                .filter(step -> step.dependencyStepIds().stream().map(state::get).allMatch(value -> value == StepState.COMPLETED))
                .sorted(stable).limit(maximumParallelism).map(Step::id).toList();
        return new ProjectPlanReadyWave(ready, blocked);
    }

    private static boolean isWaiting(StepState state) { return state == StepState.PENDING || state == StepState.READY; }
    private static boolean blocks(StepState state) { return state == StepState.FAILED || state == StepState.CANCELLED || state == StepState.BLOCKED; }

    public enum StepState { PENDING, READY, IN_PROGRESS, COMPLETED, FAILED, BLOCKED, CANCELLED }
    public record Step(String id, int sequence, List<String> dependencyStepIds, StepState state) {
        public Step {
            if (id == null || id.isBlank() || sequence < 0 || state == null) throw new IllegalArgumentException("ready-wave step is invalid");
            dependencyStepIds = dependencyStepIds == null ? List.of() : List.copyOf(dependencyStepIds);
        }
    }
}
