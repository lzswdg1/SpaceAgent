package com.spaceagent.platform.project.domain;
import java.util.List; import java.util.Map; import java.util.Objects;
public record ProjectBlueprintDocument(
        String goal, List<String> requirements, List<String> modules,
        List<String> boundaries, List<String> architectureDecisions,
        Map<String, String> commands, List<String> environmentRefs,
        List<String> conventions, List<String> forbiddenAreas,
        List<String> risks, List<String> openQuestions,
        List<String> acceptanceCriteria, List<String> evidence) {
    public ProjectBlueprintDocument {
        if (goal == null || goal.isBlank()) throw new IllegalArgumentException("goal is required");
        requirements=copy(requirements); modules=copy(modules); boundaries=copy(boundaries);
        architectureDecisions=copy(architectureDecisions); commands=Map.copyOf(Objects.requireNonNull(commands));
        environmentRefs=copy(environmentRefs); conventions=copy(conventions); forbiddenAreas=copy(forbiddenAreas);
        risks=copy(risks); openQuestions=copy(openQuestions); acceptanceCriteria=copy(acceptanceCriteria); evidence=copy(evidence);
    }
    private static List<String> copy(List<String> values){ return List.copyOf(Objects.requireNonNull(values)); }
}
