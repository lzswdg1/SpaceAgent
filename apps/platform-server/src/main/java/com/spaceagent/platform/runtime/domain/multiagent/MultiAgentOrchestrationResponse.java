package com.spaceagent.platform.runtime.domain.multiagent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** One compute-only command proposal returned by the TypeScript boundary. */
public record MultiAgentOrchestrationResponse(
        String contractVersion,
        String requestId,
        String agentRunId,
        Command command,
        Orchestration orchestration) {

    public MultiAgentOrchestrationResponse {
        if (!MultiAgentOrchestrationRequest.CONTRACT_VERSION.equals(contractVersion)) {
            throw new IllegalArgumentException("unsupported multi-agent contract version");
        }
        requireText(requestId, "requestId");
        requireText(agentRunId, "agentRunId");
        command = Objects.requireNonNull(command, "command");
        orchestration = Objects.requireNonNull(orchestration, "orchestration");
        if (!routeFor(command.kind()).equals(orchestration.route())) {
            throw new IllegalArgumentException("command kind and orchestration route do not match");
        }
    }

    public enum CommandKind {
        MODEL_REQUESTED,
        PLAN_PROPOSED,
        DELEGATE_SUBTASK,
        APPROVAL_REQUIRED,
        HANDOFF_PROPOSED,
        REVIEW_REQUIRED,
        COMPLETED
    }

    public record Command(CommandKind kind, Map<String, Object> payload) {
        public Command {
            kind = Objects.requireNonNull(kind, "kind");
            payload = Collections.unmodifiableMap(new LinkedHashMap<>(
                    Objects.requireNonNull(payload, "payload")));
            validate(kind, payload);
        }

        private static void validate(CommandKind kind, Map<String, Object> payload) {
            List<String> required = switch (kind) {
                case MODEL_REQUESTED -> List.of(
                        "modelPoolRef", "logicalCallId", "messages", "parameters");
                case PLAN_PROPOSED -> List.of("rootTaskId", "strategySummary", "steps");
                case DELEGATE_SUBTASK -> List.of(
                        "taskPlanId", "planStepId", "childTaskId", "preferredAgentId");
                case APPROVAL_REQUIRED -> List.of("scopeType", "scopeId", "reason");
                case HANDOFF_PROPOSED -> List.of("sourceAgentRunId", "targetAgentId", "summary");
                case REVIEW_REQUIRED -> List.of("taskPlanId", "planStepId", "artifactIds");
                case COMPLETED -> List.of("summary");
            };
            if (!payload.keySet().equals(Set.copyOf(required))) {
                throw new IllegalArgumentException(
                        "multi-agent command payload fields do not match " + kind);
            }
            switch (kind) {
                case MODEL_REQUESTED -> validateModelRequest(payload);
                case PLAN_PROPOSED -> validatePlan(payload);
                case DELEGATE_SUBTASK -> {
                    requireString(payload, "taskPlanId");
                    requireString(payload, "planStepId");
                    requireString(payload, "childTaskId");
                    Object preferred = payload.get("preferredAgentId");
                    if (preferred != null
                            && (!(preferred instanceof String value) || value.isBlank())) {
                        throw new IllegalArgumentException(
                                "preferredAgentId must be null or non-blank");
                    }
                }
                case APPROVAL_REQUIRED -> {
                    String scopeType = requireString(payload, "scopeType");
                    if (!List.of("TASK_PLAN", "PLAN_STEP").contains(scopeType)) {
                        throw new IllegalArgumentException("unsupported approval scopeType");
                    }
                    requireString(payload, "scopeId");
                    requireString(payload, "reason");
                }
                case COMPLETED -> requireString(payload, "summary");
                case HANDOFF_PROPOSED -> {
                    requireString(payload, "sourceAgentRunId");
                    requireString(payload, "targetAgentId");
                    requireString(payload, "summary");
                }
                case REVIEW_REQUIRED -> {
                    requireString(payload, "taskPlanId");
                    requireString(payload, "planStepId");
                    if (!(payload.get("artifactIds") instanceof List<?> artifactIds)
                            || artifactIds.isEmpty()
                            || artifactIds.stream().anyMatch(value ->
                                    !(value instanceof String text) || text.isBlank())) {
                        throw new IllegalArgumentException(
                                "artifactIds must be a non-empty string array");
                    }
                }
            }
        }

        private static void validateModelRequest(Map<String, Object> payload) {
            requireString(payload, "modelPoolRef");
            String logicalCallId = requireString(payload, "logicalCallId");
            if (logicalCallId.length() > 200) {
                throw new IllegalArgumentException(
                        "MODEL_REQUESTED logicalCallId must not exceed 200 characters");
            }
            if (!(payload.get("messages") instanceof List<?> messages)
                    || messages.isEmpty() || messages.size() > 4) {
                throw new IllegalArgumentException(
                        "MODEL_REQUESTED messages must contain between one and four entries");
            }
            for (Object value : messages) {
                if (!(value instanceof Map<?, ?> message)
                        || !message.keySet().equals(Set.of("role", "content"))) {
                    throw new IllegalArgumentException("MODEL_REQUESTED message fields are invalid");
                }
                String role = requireString(message, "role");
                String content = requireString(message, "content");
                if (!Set.of("system", "user").contains(role) || content.length() > 32_000) {
                    throw new IllegalArgumentException("MODEL_REQUESTED message is out of bounds");
                }
            }
            if (!(payload.get("parameters") instanceof Map<?, ?> parameters)
                    || !parameters.keySet().equals(Set.of(
                            "temperature", "maxOutputTokens", "responseFormat"))) {
                throw new IllegalArgumentException("MODEL_REQUESTED parameters are invalid");
            }
            Object temperature = parameters.get("temperature");
            Object maxOutputTokens = parameters.get("maxOutputTokens");
            if (!(temperature instanceof Number temperatureValue)
                    || temperatureValue.doubleValue() < 0
                    || temperatureValue.doubleValue() > 1
                    || !(maxOutputTokens instanceof Number tokenValue)
                    || tokenValue.intValue() < 64
                    || tokenValue.intValue() > 4096
                    || !"json_object".equals(parameters.get("responseFormat"))) {
                throw new IllegalArgumentException("MODEL_REQUESTED parameters are out of bounds");
            }
        }

        private static void validatePlan(Map<String, Object> payload) {
            requireString(payload, "rootTaskId");
            requireString(payload, "strategySummary");
            if (!(payload.get("steps") instanceof List<?> steps) || steps.isEmpty()) {
                throw new IllegalArgumentException("PLAN_PROPOSED steps must not be empty");
            }
            Map<String, List<String>> graph = new LinkedHashMap<>();
            for (Object value : steps) {
                if (!(value instanceof Map<?, ?> step)
                        || !step.keySet().equals(Set.of(
                                "stepKey", "goal", "dependsOnStepKeys"))) {
                    throw new IllegalArgumentException("PLAN_PROPOSED step fields are invalid");
                }
                String stepKey = requireString(step, "stepKey");
                requireString(step, "goal");
                if (!(step.get("dependsOnStepKeys") instanceof List<?> dependencies)
                        || dependencies.stream().anyMatch(
                                dependency -> !(dependency instanceof String text)
                                        || text.isBlank())) {
                    throw new IllegalArgumentException(
                            "dependsOnStepKeys must be a string array");
                }
                @SuppressWarnings("unchecked")
                List<String> dependencyKeys = (List<String>) dependencies;
                if (graph.putIfAbsent(stepKey, List.copyOf(dependencyKeys)) != null) {
                    throw new IllegalArgumentException("PLAN_PROPOSED step keys must be unique");
                }
            }
            for (Map.Entry<String, List<String>> entry : graph.entrySet()) {
                if (entry.getValue().stream().anyMatch(dependency ->
                        dependency.equals(entry.getKey()) || !graph.containsKey(dependency))) {
                    throw new IllegalArgumentException(
                            "PLAN_PROPOSED dependencies must reference another known step");
                }
            }
            Set<String> unresolved = new java.util.LinkedHashSet<>(graph.keySet());
            Set<String> resolved = new java.util.LinkedHashSet<>();
            while (!unresolved.isEmpty()) {
                List<String> ready = unresolved.stream()
                        .filter(key -> resolved.containsAll(graph.get(key)))
                        .toList();
                if (ready.isEmpty()) {
                    throw new IllegalArgumentException(
                            "PLAN_PROPOSED dependencies must be acyclic");
                }
                unresolved.removeAll(ready);
                resolved.addAll(ready);
            }
        }

        private static String requireString(Map<?, ?> payload, String field) {
            Object value = payload.get(field);
            if (!(value instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException(field + " must be a non-blank string");
            }
            return text;
        }
    }

    private static String routeFor(CommandKind kind) {
        return switch (kind) {
            case MODEL_REQUESTED -> "model";
            case PLAN_PROPOSED -> "planner";
            case DELEGATE_SUBTASK -> "delegate";
            case HANDOFF_PROPOSED -> "handoff";
            case REVIEW_REQUIRED -> "review";
            case APPROVAL_REQUIRED -> "approval";
            case COMPLETED -> "complete";
        };
    }

    public record Orchestration(String route, boolean ephemeral) {
        public Orchestration {
            if (!List.of("model", "planner", "delegate", "handoff", "review", "approval", "complete").contains(route)) {
                throw new IllegalArgumentException("unsupported orchestration route: " + route);
            }
            if (!ephemeral) {
                throw new IllegalArgumentException("TypeScript orchestration state must be ephemeral");
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
